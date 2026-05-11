#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float AlphaCutoff;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    if (AlphaCutoff > 0.0 && texel.a < AlphaCutoff) {
        discard;
    }

    float brightness = clamp(max(max(vertexColor.r, vertexColor.g), vertexColor.b), 0.0, 1.0);
    vec4 color = vec4(ColorModulator.rgb * brightness, ColorModulator.a * texel.a * vertexColor.a);
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
