# Reglas de R8 para release.
#
# ONNX Runtime (flavor `open`). SU AAR SOLO CONSERVA LA TELEMETRIA: el resto lo
# renombra R8, y la parte nativa busca clases y constructores Java por nombre
# (FindClass/GetMethodID) al devolver resultados o lanzar errores. En el mapping
# del primer release salian `OrtException -> d63` y `OrtSession$Result -> g63`,
# y se habia eliminado el constructor de OnnxTensor que usa el JNI: el release
# publicable habria cascado en la primera inferencia con NoClassDefFoundError o
# NoSuchMethodError, y la CI (que solo compila) no lo habria visto.
-keep class ai.onnxruntime.** { *; }

# LiteRT (BirdNET v2.4 en `personal` y el clasificador de imagen en ambos). Su
# AAR solo conserva lo anotado con @UsedByReflection. Se conserva el paquete
# entero por la misma razon que ONNX: el JNI no avisa en el build, avisa en el
# movil. Cuesta unos pocos KB.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
