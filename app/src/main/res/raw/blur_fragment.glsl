precision mediump float;

uniform sampler2D uTexture;
uniform float uBlurStrength;   // Driven by fold progress, 0.0 ~ 1.0
uniform float uBlurEdge;       // Blur start position (normalized, left to right)
uniform float uOuterAlpha;     // Outer-screen alpha = cos(θ)
uniform vec2 uTexSize;

varying vec2 vTexCoord;

const float MAX_RADIUS = 25.0;

void main() {
    // Distance from blur boundary for left (far from hinge) to right gradient blur
    float dist = uBlurEdge - vTexCoord.x;
    float blurFactor = clamp(dist / 0.15, 0.0, 1.0);
    float radius = uBlurStrength * MAX_RADIUS * blurFactor;

    if (radius < 0.5) {
        gl_FragColor = vec4(0.0);
        return;
    }

    // Horizontal Gaussian blur (performance-first; two-pass FBO ping-pong possible)
    vec4 color = vec4(0.0);
    float total = 0.0;
    float step = 1.0 / uTexSize.x;
    float sigma = max(radius / 4.0, 0.5);

    for (float i = -4.0; i <= 4.0; i += 1.0) {
        float weight = exp(-i * i / (2.0 * sigma * sigma));
        color += texture2D(uTexture, vTexCoord + vec2(i * step, 0.0)) * weight;
        total += weight;
    }

    float alpha = clamp(uBlurStrength * blurFactor * uOuterAlpha, 0.0, 1.0);
    gl_FragColor = vec4(color.rgb / total, alpha);
}
