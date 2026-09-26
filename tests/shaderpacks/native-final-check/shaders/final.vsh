#version 120
// SPDX-License-Identifier: LGPL-3.0-only

varying vec2 texcoord;

void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
}
