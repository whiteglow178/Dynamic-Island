package com.example.dynamicisland

import android.annotation.SuppressLint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Color
import kotlin.math.floor
import kotlin.math.sin

/**
 * Gerçekçi "cam oval" asistan animasyonu (Android 13+ / AGSL).
 *
 * Her piksel GPU'da hesaplanır:
 *  - Dumanlı siyah cam zemin, kenara yakın cam kalınlığı gölgesi
 *  - Arkada hacimli renkli sis (ışığın camın içinde yayılması)
 *  - 4 ayrı ışık dalgası: ince sıcak çekirdek + yumuşak hale + dalga ile merkez arası yarı saydam yüzey
 *  - Işıklar üst üste bindiğinde gerçek ışık gibi beyaza doygunlaşır (tonemap)
 *  - Fresnel kenar ışığı (üstte güçlü, altta zayıf), kenarda kırmızı→mavi renk sapması
 *  - Üstte cam parlaması, altta dalga renklerinin yansıması, kenarda iç ışık sızması
 *  - "Konuşma enerjisi": dalga boyu ve parlaklık düzensiz nefes alır (CPU'da hesaplanıp uniform olarak gelir)
 * Zaman sürekli akar; döngü başa sarmadığı için tekrar eden bir "loop" hissi yoktur.
 */
private val GLASS_ORB_AGSL = """
uniform float2 size;
uniform float time;
uniform float energy;
uniform float dp;
uniform float3 c0; uniform float3 c1; uniform float3 c2; uniform float3 c3;
uniform float3 c4; uniform float3 c5; uniform float3 c6;

const float TAU = 6.2831853;
const float PI = 3.14159265;

float3 pal(float x) {
    x = fract(x) * 7.0;
    float i = floor(x);
    float f = x - i;
    f = f * f * (3.0 - 2.0 * f);
    if (i < 1.0) return mix(c0, c1, f);
    if (i < 2.0) return mix(c1, c2, f);
    if (i < 3.0) return mix(c2, c3, f);
    if (i < 4.0) return mix(c3, c4, f);
    if (i < 5.0) return mix(c4, c5, f);
    if (i < 6.0) return mix(c5, c6, f);
    return mix(c6, c0, f);
}

float hash1(float n) { return fract(sin(n) * 43758.5453); }
float noise1(float x) {
    float i = floor(x);
    float f = x - i;
    f = f * f * (3.0 - 2.0 * f);
    return mix(hash1(i), hash1(i + 1.0), f);
}

float waveY(float u, float t, float fi, float amp) {
    float ph = fi * 1.913;
    float y = sin(u * TAU * (0.85 + 0.22 * fi) - t * (1.35 + 0.31 * fi) + ph) * 0.60
            + sin(u * TAU * (1.9 + 0.37 * fi) + t * (1.9 - 0.27 * fi) + ph * 2.3) * 0.28
            + (noise1(u * 2.6 + t * 0.8 + fi * 7.31) - 0.5) * 0.55;
    float env = pow(max(sin(PI * clamp(u, 0.0, 1.0)), 0.0), 1.6);
    return y * env * amp;
}

half4 main(float2 fragCoord) {
    float W = size.x;
    float H = size.y;
    float t = time;
    float e = energy;

    // --- oval (stadyum) SDF ve kenar normali
    float2 hs = size * 0.5;
    float r = min(W, H) * 0.5;
    float2 q = abs(fragCoord - hs) - (hs - float2(r));
    float2 mq = max(q, float2(0.0));
    float lq = length(mq);
    float d = lq + min(max(q.x, q.y), 0.0) - r;
    float ny = (mq.y / (lq + 1e-4)) * sign(fragCoord.y - hs.y);
    float dn = -d;

    float u = fragCoord.x / W;
    float v = fragCoord.y / H;
    float cy = H * 0.5;
    float su = max(sin(PI * u), 0.0);

    // --- zemin: dumanlı siyah cam
    float3 bg = float3(0.008, 0.009, 0.014) + smoothstep(0.45, 1.0, v) * 0.045 * float3(0.8, 0.85, 1.0);

    // --- arkadaki hacimli renkli sis
    float3 light = float3(0.0);
    float fy = (fragCoord.y - cy) / (H * (0.20 + 0.06 * e));
    float fog = exp(-fy * fy) * pow(su, 0.8);
    light += pal(u * 0.55 + t * 0.045 + 0.5) * fog * 0.24 * e;

    // --- ışık dalgaları
    float envw = pow(su, 1.2);
    float eps = 1.0 / W;
    for (int k = 0; k < 4; k++) {
        float fi = float(k);
        float amp = H * (0.20 + 0.03 * fi) * e;
        float yw = waveY(u, t, fi, amp);
        float dy = waveY(u + eps, t, fi, amp) - yw;
        float dv = (fragCoord.y - cy) - yw;
        float dist = abs(dv) / sqrt(1.0 + dy * dy);
        float wc = dp * (0.7 + 1.5 * e * envw);
        float cd = dist / wc;
        float core = exp(-cd * cd);
        float halo = exp(-dist / (wc * 5.0)) * 0.30;
        float ayw = max(abs(yw), 1.0);
        float syw = yw >= 0.0 ? ayw : -ayw;
        float s = (fragCoord.y - cy) / syw;
        float sheet = smoothstep(0.0, 1.0, s) * (1.0 - smoothstep(0.92, 1.0 + 1.2 / ayw, s));
        sheet = sheet * s * s * 0.42 * envw;
        float3 col = pal(u * 0.62 + t * 0.06 + fi * 0.29);
        light += col * ((core * 0.95 + halo) * envw * (0.75 + 0.25 * e) + sheet);
    }

    // --- beyaz-sıcak merkez çizgisi
    float cdist = abs(fragCoord.y - cy - waveY(u, t, 0.5, H * 0.05 * e));
    float cc = cdist / (dp * 0.6);
    light += float3((exp(-cc * cc) * 0.55 + exp(-cdist / (dp * 4.0)) * 0.10) * su * su * e);

    // ışık doygunluğu: üst üste binen renkler beyaza gider
    light = float3(1.0) - exp(-light * 1.6);

    // cam kalınlığı: kenara yakın iç gölge
    float shade = 1.0 - 0.55 * exp(-dn / (dp * 9.0));
    float3 col = bg * shade + light * (1.0 - 0.35 * exp(-dn / (dp * 6.0)));

    // --- cam: fresnel kenar + renk sapması
    float top = 0.45 + 0.55 * pow(clamp(-ny, 0.0, 1.0), 0.8) + 0.25 * clamp(ny, 0.0, 1.0);
    float fr = 0.7 * dp;
    float k0 = dp * 0.9;
    float3 rim = float3(
        exp(-max(dn - fr * 0.2, 0.0) / k0),
        exp(-max(dn - fr * 0.7, 0.0) / k0),
        exp(-max(dn - fr * 1.3, 0.0) / k0)
    ) * (0.55 * top);
    float wide = exp(-dn / (dp * 5.0)) * 0.10 * top;
    float3 tint = pal(u * 0.62 + t * 0.06) * exp(-dn / (dp * 3.5)) * 0.22 * e * sqrt(su);
    float2 ge = (fragCoord - float2(W * 0.5, H * 0.13)) / float2(W * 0.40, H * 0.13);
    float gloss = (1.0 - smoothstep(0.30, 1.0, length(ge))) * 0.11;
    float2 re = (fragCoord - float2(W * 0.5, H * 0.92)) / float2(W * 0.36, H * 0.07);
    float refl = 1.0 - smoothstep(0.2, 1.0, length(re));
    float3 reflc = pal(u * 0.62 + t * 0.06 + 0.3) * refl * 0.18 * e + float3(refl * 0.04);
    float3 hl = clamp(rim + float3(wide + gloss) + tint + reflc, 0.0, 1.0);
    col = float3(1.0) - (float3(1.0) - col) * (float3(1.0) - hl);

    // bantlaşmayı önleyen titreşim (dither)
    float ign = fract(52.9829189 * fract(dot(fragCoord, float2(0.06711056, 0.00583715))));
    col += (ign - 0.5) / 255.0;

    float a = clamp(0.5 - d, 0.0, 1.0);
    return half4(half3(clamp(col, 0.0, 1.0) * a), half(a));
}
"""

fun glassOrbShaderSupported(): Boolean = Build.VERSION.SDK_INT >= 33

private fun hash1(n: Float): Float { val s = sin(n) * 43758.5453f; return s - floor(s) }
private fun noise1(x: Float): Float {
    val i = floor(x); var f = x - i; f = f * f * (3f - 2f * f)
    return hash1(i) * (1f - f) + hash1(i + 1f) * f
}

/** Konuşma / dinleme hissi: yavaş nefes + düzensiz vurgular. */
private fun voiceEnergy(t: Float): Float =
    (0.55f + 0.25f * sin(t * 1.7f) + 0.35f * (noise1(t * 2.3f) - 0.5f) * 2f).coerceIn(0.25f, 1.15f)

private fun RuntimeShader.setColor3(name: String, c: Color) = setFloatUniform(name, c.red, c.green, c.blue)

@SuppressLint("NewApi")
@Composable
fun AssistantGlassOrbRealistic() {
    val shader = remember { RuntimeShader(GLASS_ORB_AGSL) }
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                // Bir saatte bir sıfırlanır: float hassasiyeti uzun sürede bozulmasın
                time.floatValue = ((now - start) % 3_600_000_000_000L) / 1_000_000_000f
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas
        val t = time.floatValue                       // yalnızca çizim fazında okunur: yeniden kompozisyon olmaz
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("time", t)
        shader.setFloatUniform("energy", voiceEnergy(t))
        shader.setFloatUniform("dp", density)
        // Renk düzenleyicideki asistan renkleri (beyaz olan Renk 7 hariç; beyazı ışığın kendisi üretir)
        shader.setColor3("c0", Pal.c("asst_1"))
        shader.setColor3("c1", Pal.c("asst_2"))
        shader.setColor3("c2", Pal.c("asst_3"))
        shader.setColor3("c3", Pal.c("asst_4"))
        shader.setColor3("c4", Pal.c("asst_5"))
        shader.setColor3("c5", Pal.c("asst_6"))
        shader.setColor3("c6", Pal.c("asst_8"))
        drawRect(ShaderBrush(shader))
    }
}
