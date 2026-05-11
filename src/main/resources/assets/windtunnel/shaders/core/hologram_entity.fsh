#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 HologramColor;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    float vertexBrightness = max(max(vertexColor.r, vertexColor.g), vertexColor.b);
    float lightBrightness = max(max(lightMapColor.r, lightMapColor.g), lightMapColor.b);
    float brightness = clamp(vertexBrightness * lightBrightness, 0.0, 1.0);
    vec4 color = vec4(HologramColor.rgb * brightness, HologramColor.a * texel.a * vertexColor.a);

    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
