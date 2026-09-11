attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;

void main() {
    // Flip Y: GL texture origin bottom-left, Bitmap content origin top-left
    vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
