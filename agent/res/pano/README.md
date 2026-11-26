# Main menu panorama assets

Each panorama is a folder named after its setting choice. Add six square PNG cube faces:

```text
agent/res/pano/<Name>/panorama_0.png
agent/res/pano/<Name>/panorama_1.png
agent/res/pano/<Name>/panorama_2.png
agent/res/pano/<Name>/panorama_3.png
agent/res/pano/<Name>/panorama_4.png
agent/res/pano/<Name>/panorama_5.png
```

Use vanilla resource pack face order and orientation: `0` front (+Z), `1` right (+X), `2` back (-Z), `3` left (-X),
`4` top (+Y), `5` bottom (-Y). Faces must have matching square dimensions; 1024 x 1024 PNGs are a good source size.
The agent loads these images and resamples the cube map off-thread before rendering it. Until a folder exists, the loose `<Name>.jpg`
cylindrical strips (the 2026-11-20 captures) are read instead.
