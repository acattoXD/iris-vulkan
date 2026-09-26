#version 120
// SPDX-License-Identifier: LGPL-3.0-only

uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;
uniform float frameTimeCounter;

varying vec2 texcoord;

void main() {
    vec2 size = max(vec2(viewWidth, viewHeight), vec2(1.0));
    vec2 screenUV = gl_FragCoord.xy / size;

    // Each half shows the same complete scene. This makes a capture test compare
    // sampled scene colors with the known tone operation in the other half.
    vec2 sceneUV = vec2(fract(texcoord.x * 2.0), texcoord.y);
    vec3 scene = texture2D(colortex0, sceneUV).rgb;
    vec3 tone = clamp(scene * vec3(0.35, 0.85, 1.0) + vec3(0.02, 0.05, 0.06), 0.0, 1.0);
    vec3 color = screenUV.x < 0.5 ? scene : tone;

    // Four device pixels wide, so stale size uniforms are visible after resize.
    if (abs(gl_FragCoord.x - viewWidth * 0.5) < 2.0) {
        color = vec3(1.0, 0.0, 1.0);
    }

    // An eight-second sweep proves the frame-time uniform is live. This band
    // occupies 3.5% of the height and stays separate from the comparison area.
    if (screenUV.y < 0.035) {
        float sweep = 0.1 + 0.8 * fract(frameTimeCounter / 8.0);
        color = screenUV.x < sweep ? vec3(1.0, 0.8, 0.0) : vec3(0.08, 0.04, 0.0);
    }

    gl_FragColor = vec4(color, 1.0);
}
