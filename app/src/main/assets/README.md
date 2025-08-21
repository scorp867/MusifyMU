# VOSK model assets

Place the VOSK English small model here, unzipped, under a folder named `model-en-us`.

Expected path structure:

model-en-us/
	conf/
	dict/
	graph/
	ivector/
	rescore/
	README

In code, `StorageService.unpack(context, "model-en-us", "model", ...)` will copy this model to `filesDir/model` on first run and load it from there.