.PHONY: all clean

all: TMCMR.jar.urn

clean:
	rm -rf bin TMCMR.jar .src.lst

TMCMR.jar: $(shell find src)
	rm -rf bin TMCMR.jar
	cp -r src bin
	find src/main/java -name '*.java' >.src.lst
	javac -source 1.6 -target 1.6 -d bin @.src.lst
	mkdir -p bin/META-INF
	echo 'Version: 1.0' >bin/META-INF/MANIFEST.MF
	echo 'Main-Class: togos.minecraft.maprend.RegionRenderer' >>bin/META-INF/MANIFEST.MF
	cd bin ; zip -9 -r ../TMCMR.jar . ; cd ..

%.urn: % TMCMR.jar
	java -cp TMCMR.jar togos.minecraft.maprend.io.IDFile "$<" >"$@"
