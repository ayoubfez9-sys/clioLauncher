package fr.ayoub.cliolauncher.render

/**
 * Shaders OpenGL ES 3.0.
 * (Les memes textes servent a l'apercu WebGL2 : ne pas changer les marqueurs BEGIN/END.)
 */
object Shaders {

    // BEGIN CAR_VS
    const val CAR_VS = """#version 300 es
precision highp float;
uniform mat4 uModel;
uniform mat4 uViewProj;
in vec3 aPos;
in vec3 aNormal;
out vec3 vWorld;
out vec3 vN;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vN = mat3(uModel) * aNormal;
    gl_Position = uViewProj * w;
}
"""
    // END CAR_VS

    // BEGIN CAR_FS
    const val CAR_FS = """#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vN;
uniform vec3 uCam;
uniform vec3 uBase;      // couleur lineaire
uniform float uRough;    // 0 = miroir, 1 = mat
uniform float uMetal;    // 0 = plastique/peinture, 1 = metal
uniform float uCoat;     // vernis (reflets) de 0 a 1
uniform vec3 uEmissive;  // lumiere propre (phares, etape 3)
uniform float uAlpha;    // 1 = opaque, < 1 = vitres
uniform int uLite;       // 1 = mode allege
uniform vec3 uAccent;    // bleu de l'anneau
uniform int uPaint;      // 1 = carrosserie
uniform int uDoors;      // portes ouvertes (bits : 1 AVG, 2 AVD, 4 ARG, 8 ARD, 16 coffre)
uniform float uTime;
uniform vec2 uYaw;       // cos, sin de la rotation de la voiture
uniform vec2 uHalf;      // demi-largeur, demi-longueur
out vec4 fragColor;

// Zone d'une portiere, dans le repere de la voiture (avant = +Z, gauche = +X)
float doorZone(vec3 p, float side, float zc, float zh) {
    float s = smoothstep(0.45, 0.80, p.x * side / uHalf.x);
    float z = 1.0 - smoothstep(zh - 0.06, zh + 0.04, abs(p.z - zc));
    float y = smoothstep(0.22, 0.32, p.y) * (1.0 - smoothstep(0.98, 1.06, p.y));
    return s * z * y;
}

float doorGlow(vec3 w) {
    if (uDoors == 0) return 0.0;
    vec3 p = vec3(uYaw.x * w.x - uYaw.y * w.z, w.y, uYaw.y * w.x + uYaw.x * w.z);
    float g = 0.0;
    if ((uDoors & 1) != 0) g = max(g, doorZone(p,  1.0,  0.42, 0.50));
    if ((uDoors & 2) != 0) g = max(g, doorZone(p, -1.0,  0.42, 0.50));
    if ((uDoors & 4) != 0) g = max(g, doorZone(p,  1.0, -0.62, 0.48));
    if ((uDoors & 8) != 0) g = max(g, doorZone(p, -1.0, -0.62, 0.48));
    if ((uDoors & 16) != 0) {
        float back = smoothstep(-uHalf.y + 0.45, -uHalf.y + 0.25, p.z);
        g = max(g, back * smoothstep(0.68, 0.78, p.y) * (1.0 - smoothstep(1.40, 1.50, p.y)));
    }
    return g * (0.55 + 0.45 * sin(uTime * 5.0));
}

// Studio virtuel : degrade sombre, grande boite a lumiere au plafond,
// deux bandes lumineuses laterales et lueur bleue de l'anneau en bas.
vec3 env(vec3 d) {
    float y = d.y;
    vec3 c = mix(vec3(0.008, 0.010, 0.016), vec3(0.17, 0.19, 0.23), smoothstep(-0.15, 0.30, y));
    c = mix(c, vec3(0.045, 0.055, 0.075), smoothstep(0.35, 0.75, y));
    c += vec3(1.6) * smoothstep(0.80, 0.95, y);
    float a = atan(d.z, d.x);
    float band = smoothstep(0.20, 0.02, abs(y - 0.22));
    c += vec3(0.80, 0.90, 1.0) * 1.6 * band * smoothstep(0.90, 1.0, cos(a - 0.9));
    c += vec3(0.80, 0.90, 1.0) * 1.1 * band * smoothstep(0.92, 1.0, cos(a + 2.3));
    c += uAccent * 0.22 * smoothstep(-0.02, -0.40, y);
    return c;
}

void main() {
    vec3 V = normalize(uCam - vWorld);
    vec3 N = normalize(vN);
    if (dot(N, V) < 0.0) N = -N;          // pieces vues des deux cotes
    vec3 L = normalize(vec3(0.35, 0.90, 0.45));
    vec3 H = normalize(L + V);
    float NL = max(dot(N, L), 0.0);
    float NV = max(dot(N, V), 0.0);
    float NH = max(dot(N, H), 0.0);

    vec3 base = uBase; float rough = uRough; float metal = uMetal; float coat = uCoat;
    vec3 albedo = base * (1.0 - metal);
    vec3 ambient = mix(vec3(0.030, 0.038, 0.055), vec3(0.20, 0.22, 0.27), 0.5 + 0.5 * N.y);
    vec3 col = albedo * (ambient + vec3(1.0, 0.97, 0.93) * NL * 0.95);

    vec3 F0 = mix(vec3(0.04), base, metal);
    float shin = mix(300.0, 6.0, rough);
    col += F0 * pow(NH, shin) * (shin + 8.0) / 30.0 * NL;

    float fres = pow(1.0 - NV, 5.0);
    if (uLite == 0) {
        vec3 e = env(reflect(-V, N));
        col += e * (F0 + (1.0 - F0) * fres) * (1.0 - 0.85 * rough) * mix(0.35, 1.0, metal);
        col += e * coat * (0.08 + 0.92 * fres);
    } else {
        col += vec3(0.20, 0.23, 0.28) * coat * fres;
        col += vec3(0.6) * coat * pow(NH, 80.0) * NL;
        col += F0 * vec3(0.30, 0.32, 0.36) * (1.0 - 0.6 * rough);
    }
    col += uEmissive;
    if (uPaint == 1) col = mix(col, vec3(1.0, 0.40, 0.03), doorGlow(vWorld) * 0.85);
    col = vec3(1.0) - exp(-col * 1.35);   // compression douce des hautes lumieres
    col = pow(col, vec3(1.0 / 2.2));
    fragColor = vec4(col * uAlpha, uAlpha);
}
"""
    // END CAR_FS

    // BEGIN FLOOR_VS
    const val FLOOR_VS = """#version 300 es
precision highp float;
uniform mat4 uViewProj;
uniform float uExt;
in vec2 aXZ;
out vec2 vP;
void main() {
    vP = aXZ * uExt;
    gl_Position = uViewProj * vec4(vP.x, -0.004, vP.y, 1.0);
}
"""
    // END FLOOR_VS

    // BEGIN FLOOR_FS
    const val FLOOR_FS = """#version 300 es
precision highp float;
in vec2 vP;
uniform float uR;        // rayon du plateau
uniform vec3 uAccent;    // bleu (espace ecran)
uniform vec2 uYaw;       // cos, sin de la rotation de la voiture
uniform vec2 uHalf;      // demi-largeur, demi-longueur de la voiture
uniform int uLite;
uniform float uBeam;     // 0 = phares eteints, 1 = allumes
uniform int uDoors;
uniform float uTime;
out vec4 fragColor;

// Faisceau d'un phare au sol (repere voiture : avant = +Z)
float beam(vec2 q, vec2 o) {
    vec2 d = q - o;
    if (d.y <= 0.0) return 0.0;
    float width = 0.12 + 0.42 * d.y;
    float cone = 1.0 - smoothstep(0.55, 1.0, abs(d.x) / width);
    return cone * smoothstep(0.0, 0.35, d.y) * exp(-d.y * 0.42);
}

float pool(vec2 q, vec2 c, float rad) {
    return exp(-pow(length(q - c) / rad, 2.0));
}

void main() {
    float r = length(vP) / uR;
    float disc = 1.0 - smoothstep(0.988, 1.0, r);
    vec3 plate = mix(vec3(0.085, 0.110, 0.155), vec3(0.035, 0.045, 0.065), smoothstep(0.0, 1.0, r));
    if (uLite == 0) {
        plate += vec3(0.010, 0.014, 0.022) * smoothstep(0.6, 1.0, sin(r * 140.0)) * (1.0 - r);
    }

    // ombre douce sous la voiture (tourne avec elle)
    vec2 q = vec2(uYaw.x * vP.x - uYaw.y * vP.y, uYaw.y * vP.x + uYaw.x * vP.y);
    vec2 dd = abs(q) - uHalf;
    float sd = length(max(dd, 0.0)) + min(max(dd.x, dd.y), 0.0);
    plate *= 1.0 - 0.88 * (1.0 - smoothstep(-0.25, 0.55, sd));

    // phares
    float fade = 1.0 - smoothstep(1.0, 1.45, r);
    float hb = 0.0;
    if (uBeam > 0.0) {
        hb = beam(q, vec2(uHalf.x * 0.62, uHalf.y * 0.90)) + beam(q, vec2(-uHalf.x * 0.62, uHalf.y * 0.90));
        hb = min(hb, 1.2) * uBeam * fade;
    }
    // portes ouvertes : halo orange au sol a cote de la portiere
    float dp = 0.0;
    if (uDoors != 0) {
        float side = uHalf.x + 0.55;
        if ((uDoors & 1) != 0) dp += pool(q, vec2( side,  0.42), 0.55);
        if ((uDoors & 2) != 0) dp += pool(q, vec2(-side,  0.42), 0.55);
        if ((uDoors & 4) != 0) dp += pool(q, vec2( side, -0.62), 0.55);
        if ((uDoors & 8) != 0) dp += pool(q, vec2(-side, -0.62), 0.55);
        if ((uDoors & 16) != 0) dp += pool(q, vec2(0.0, -uHalf.y - 0.55), 0.6);
        dp = min(dp, 1.0) * (0.55 + 0.45 * sin(uTime * 5.0)) * fade;
    }

    float ring = exp(-pow((r - 1.0) / 0.010, 2.0));
    float glow = exp(-pow((r - 1.0) / 0.055, 2.0)) * 0.45;
    if (uLite == 0) glow += exp(-abs(r - 1.0) / 0.16) * 0.22;
    float inner = exp(-pow((r - 0.86) / 0.005, 2.0)) * 0.30 * disc;

    vec3 light = uAccent * (ring * 1.4 + glow + inner)
               + vec3(1.0, 0.92, 0.78) * hb * 0.75
               + vec3(1.0, 0.55, 0.08) * dp * 0.85;
    float a = clamp(max(disc, ring + glow + hb * 0.75 + dp * 0.85), 0.0, 1.0);
    vec3 c = plate * disc + light;
    fragColor = vec4(min(c, vec3(a)), a);   // couleurs premultipliees (fond transparent)
}
"""
    // END FLOOR_FS
}
