#version 150

uniform vec4 ColorModulator;
uniform float GameTime;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    float along = texCoord0.x;
    float across = abs(texCoord0.y * 2.0 - 1.0);

    // A white-hot core inside a soft halo of the bolt's own colour.
    float core = exp(-across * across * 22.0);
    float halo = exp(-across * across * 4.0);

    // Travelling pulses and a fast flicker keep the bolt crawling.
    float pulse = 0.65 + 0.35 * sin(along * 38.0 - GameTime * 9000.0);
    float flicker = 0.8 + 0.2 * sin(GameTime * 26000.0 + along * 11.0);

    // Fade out towards both endpoints.
    float ends = smoothstep(0.0, 0.04, along) * smoothstep(0.0, 0.04, 1.0 - along);

    float intensity = (halo * 0.55 * pulse + core) * flicker * ends;
    vec3 color = mix(vertexColor.rgb, vec3(1.0), core * 0.85);
    float alpha = intensity * vertexColor.a;
    if (alpha < 0.01) {
        discard;
    }
    fragColor = vec4(color, alpha) * ColorModulator;
}
