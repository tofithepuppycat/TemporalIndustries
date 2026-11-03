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
    float core = exp(-across * across * 24.0);
    float halo = exp(-across * across * 4.5);

    // The channel shimmers a little while it is lit.
    float flicker = 0.9 + 0.1 * sin(GameTime * 30000.0 + along * 17.0);

    // Fade out towards both endpoints of the main channel.
    float ends = smoothstep(0.0, 0.03, along) * smoothstep(0.0, 0.03, 1.0 - along);

    float intensity = (halo * 0.7 + core) * flicker * ends;
    vec3 color = mix(vertexColor.rgb, vec3(1.0), core * 0.9);
    float alpha = intensity * vertexColor.a;
    if (alpha < 0.01) {
        discard;
    }
    fragColor = vec4(color, alpha) * ColorModulator;
}
