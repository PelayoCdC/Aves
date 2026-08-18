# Aves

App Android de identificación de aves por **canto** y por **foto**, con una
**Pokédex** cuyas rarezas se calculan a partir de datos reales de ocurrencia.

Estado: **Fase 4** — identificación por canto con filtro geo-temporal, catálogo
de 6.419 especies, colección persistida por sesiones y rarezas calculadas sobre
datos reales de ocurrencia.

---

## Arranque rápido

En un clon limpio hacen falta **tres pasos, en este orden**:

```bash
powershell -File scripts/fetch-models.ps1
```

```bash
powershell -File scripts/build-catalog.ps1
```

```bash
gradlew.bat :app:installPersonalDebug
```

Ni los modelos ni el catálogo están en el repositorio, y no es solo por tamaño:
los modelos de BirdNET son CC BY-NC-SA 4.0 y el catálogo deriva de ellos y de la
taxonomía de eBird (ver [Licencias](#licencias)).

El orden importa: `build-catalog.ps1` lee `taxo_code.txt` y `labels_es.txt`, que
los trae el primer script.

Si falta algo, la app **no se rompe en silencio**. Sin modelos, al pulsar
"escuchar" avisa de que hay que ejecutar `fetch-models.ps1`. Sin catálogo, la
resolución de especies falla con un mensaje que dice exactamente eso.

---

## Licencias — léelo antes de tocar nada

Este proyecto mezcla código propio con modelos de terceros que **no** comparten
su licencia. Es la restricción que más condiciona el diseño.

| Componente | Licencia | ¿Comercial? |
|---|---|---|
| Código de este repositorio | (a elegir) | Sí |
| Modelos BirdNET v2.4 | CC BY-NC-SA 4.0 | **No** |
| Google AIY birds_V1 **v3** (Fase 5) | Apache 2.0 | Sí |
| GBIF API | sin restricción NC | Sí |
| eBird API | no comercial | **No** |
| xeno-canto | no comercial | **No** |

> **Trampa del modelo de imagen.** Cuando llegue la Fase 5, hay que usar la
> **variación 3** de `google/aiy/tfLite/vision-classifier-birds-v1` y solo esa.
> Su changelog dice literalmente que la v3 *"Fixes out-of-order logit to
> labelmap mapping"*: las versiones 1 y 2 devuelven la especie equivocada en
> silencio. Confirmado en origen: Apache 2.0, 3,4 MB. La descarga pide cuenta
> de Kaggle.

BirdNET es de la K. Lisa Yang Center for Conservation Bioacoustics (Cornell Lab
of Ornithology). Uso personal, educativo y de investigación: explícitamente
permitido. Uso comercial: hay que pedir permiso a `ccb-birdnet@cornell.edu`.
**La atribución es requisito de licencia**, por eso la pantalla de
identificación la muestra siempre y no es ocultable.

### Cómo se sostiene esto en el build

Dos *product flavors*, un solo código fuente:

- **`personal`** — enlaza `:core:ml:birdnet`. 6.419 especies. No publicable.
- **`open`** — no lo enlaza. El modelo CC BY-NC-SA **ni siquiera está en el
  APK**. Publicable, pero hoy sin clasificador de audio (ver Fase 7).

```bash
gradlew.bat :app:assemblePersonalDebug
```

```bash
gradlew.bat :app:assembleOpenDebug
```

La elección no cuesta ni una comprobación en runtime: cada flavor compila su
propio `AudioClassifierProvider.kt`.

---

## Arquitectura

```
app/                    ensamblaje, navegación, flavors
build-logic/            convention plugins (aves.android.library, .compose, ...)
core/
  common/  model/       Kotlin puro, cero Android
  designsystem/         tema M3
  audio/                AudioRecord + ring buffer. No sabe nada de ML
  database/             Room. KSP aislado aquí
  data/                 repositorios: la frontera de las features
  ml/api/               interfaces. Cero dependencias de LiteRT
  ml/birdnet/           implementación CC BY-NC-SA
feature/
  identify/  pokedex/  review/  settings/
```

El módulo que importa es **`:core:ml:api`**. Define *qué* hace un clasificador,
nunca *cómo*. Tres decisiones concretas lo hacen funcionar:

1. **El modelo declara su formato de audio**, no la app. `AudioCaptureEngine` se
   configura leyendo `requiredSampleRate` y `requiredSampleCount` del
   clasificador. No hay ningún `48000` escrito en la capa de captura. Pasar a un
   modelo de 32 kHz y 5 s no toca ni una línea de `:core:audio`.
2. **`ModelDescriptor.allowsCommercialUse` es un dato en runtime**, no un
   comentario. Permite un test que falle el build de `open` si se cuela un
   modelo no comercial.
3. **Fuera de `:core:ml` nadie ve un índice de tensor.** Los resultados llegan ya
   traducidos a `speciesId` canónico.

Regla que no se debe romper: si algún día hay que añadir LiteRT a
`:core:ml:api`, es señal de que la abstracción se está rompiendo.

---

## La normalización de entrada — RESUELTO

Durante un tiempo este fue el riesgo técnico número uno: no se sabía si BirdNET
esperaba las muestras normalizadas a [-1,1] o crudas (±32768). Equivocarse no
habría producido ningún error visible — el modelo simplemente habría acertado
peor, en silencio, sin excepción ni log.

**Respuesta: da igual. BirdNET v2.4 es invariante a la escala de entrada.**

Cómo se determinó, en dos pasos:

1. **Inspección del grafo.** Los nombres de operación están embebidos en el
   flatbuffer del `.tflite`. El frontend `model/MEL_SPEC1` contiene
   `Min`, `Max`, `Sub`, `Sub_1` y `truediv`: el patrón de una normalización
   min-max `(x - min) / (max - min)`. Una normalización así elimina cualquier
   ganancia constante, tanto si actúa sobre el espectro de potencia (factor
   multiplicativo) como sobre uno comprimido (offset aditivo).

2. **Verificación ejecutando el modelo real.**
   [`InputScalingRegressionTest`](core/ml/birdnet/src/androidTest/kotlin/dev/pelayo/aves/core/ml/birdnet/InputScalingRegressionTest.kt)
   pasa la misma señal a escalas que difieren en cinco órdenes de magnitud
   (×0,013 frente a ×977, y ×1 frente a ×32768) y comprueba que las
   puntuaciones y el orden de candidatos son idénticos.

Lo elegante de esta comprobación es que **no necesita ninguna grabación de
aves**. La invariancia de escala es una propiedad del modelo, no del contenido:
si se cumple con una señal sintética, se cumple con un mirlo. Eso hace el test
reproducible en cualquier máquina, sin descargar audio con licencia.

`PcmScaling` se conserva —y el test con él— por dos razones: documenta la
propiedad y protege de regresiones. Si algún día se cambia de modelo, el nuevo
podría no ser invariante y entonces esto vuelve a importar.

```bash
gradlew.bat :core:ml:birdnet:connectedDebugAndroidTest
```

---

## El catálogo y el cross-walk taxonómico

El catálogo se genera cruzando tres fuentes con `scripts/build-catalog.ps1`:

```bash
powershell -File scripts/build-catalog.ps1
```

1. **Taxonomía de eBird** (API pública, sin key) — nombres en español, familia, orden.
2. **`taxo_code.txt` de BirdNET** — código eBird de cada clase del modelo.
3. **`labels_es.txt` de BirdNET** — etiqueta cruda de cada clase.

Resultado del cruce, medido:

| | clases |
|---|---|
| Mapean por código eBird directo | 6.383 |
| **Código eBird caducado**, recuperadas por nombre científico | 38 |
| Requirieron decisión manual | 1 |
| Sin mapear (correcto) | 101 |

**6.419 especies de ave. Cero aves perdidas.**

Las 101 sin mapear se reparten en **11 clases de no-evento** (perro, motor,
sirena, disparo, ruido, ambiente, fuegos artificiales, herramientas y las tres
de humano) y **90 animales que no son aves**: ranas, sapos, grillos, katídidos,
un ciervo y algún mono, que BirdNET incluye a propósito.

Dos trampas que costaron tiempo y conviene no repetir:

- **Los no-eventos son 11, no 7.** "Human vocal", "Human non-vocal",
  "Human whistle" y "Power tools" *parecen* binomios latinos, así que una
  expresión regular de "Género especie" los cuenta como especies.
- **Sin filtrar por código taxonómico, las ranas entran en la colección.** Los
  códigos de ave son de eBird; los de no-ave empiezan por `t-`.

El único caso manual fue *Parus minor* (Carbonero Japonés): eBird lo agrupó
dentro de *Parus major* y ya no existe ni por código ni por nombre. Vive en una
lista de excepciones auditadas dentro del script, con el motivo anotado.

### Por qué no hay base de datos prepoblada

El plan preveía generar un `.db` en build y cargarlo con `createFromAsset()`. Se
descartó: ese mecanismo exige que el fichero lleve el `identityHash` exacto que
Room deriva del esquema, y generarlo fuera de Room falla **en el móvil del
usuario**, no en el build. Se importan ~13.000 filas desde TSV en una única
transacción.

**Medido, no estimado:** la primera versión tardaba **10,9 s** en emulador.
Sembraba también `species_names` con los mismos nombres que ya lleva
`defaultCommonName` — 6.419 filas redundantes. Quitándolas bajó a **2,3 s**.

La lección no es el número sino que mi estimación inicial ("milisegundos") era
falsa por un factor de 4.000: salía de contar *una transacción* y olvidar que
dentro hay ~19.000 `INSERT` individuales. Solo se vio al ejecutarlo.

### La regla que sostiene la Pokédex

**Una observación por especie y sesión**, con la mejor puntuación alcanzada. La
regla vive en `ObservationDao.recordBestInSession` como transacción atómica, no
solo en el repositorio: una regla que solo vive en código se salta tarde o
temprano. Sin ella, veinte minutos de escucha en el jardín generarían cientos de
filas del mismo mirlo y la colección no significaría nada.

Y las decisiones del usuario mandan sobre el modelo: una detección mejor
actualiza la puntuación pero **no resucita** algo que ya habías descartado.

---

## El filtro de contexto y las rarezas

En un sitio y una fecha concretos solo unos cientos de las 6.419 especies del
catálogo son plausibles. Filtrar por ahí es la mayor mejora de precisión
disponible sin tocar el modelo.

Hay **dos priors** tras la misma interfaz `ContextPriorProvider`:

- **`personal`** usa el meta-modelo `MData` de BirdNET. Se prefiere porque se
  entrenó *junto* al clasificador: sus probabilidades están en la misma escala
  que las predicciones que va a filtrar.
- **`open`** usa el derivado de GBIF, que es el único distribuible.

### Verificado ejecutando el modelo, no asumido

El riesgo era que la salida del meta-modelo fuesen **logits** en vez de
probabilidades. En ese caso todos los valores rondarían 0,5, todas las especies
caerían en el multiplicador 1,0 y el prior **no filtraría nada** — sin
excepción, sin log, sin síntoma. La mejora que justifica toda la fase
simplemente no existiría.

`BirdNetContextPriorTest` lo zanja con comprobaciones ornitológicas: el número
de especies plausibles en Asturias en junio son cientos y no miles, Asturias y
el Amazonas comparten menos del 50% de especies, y el petirrojo europeo es
plausible donde debe serlo mientras que un tucán no.

### El cruce con GBIF

GBIF devuelve `"Turdus merula Linnaeus, 1758"`; quitando la autoría queda el
binomio, que es exactamente nuestro `speciesId`. Eso evita tener que poblar
`gbifSpeciesKey` en las 6.419 especies, que habría costado miles de llamadas.

La contrapartida está asumida: las revisiones taxonómicas hacen que algunas
especies no crucen. Esas caen al suelo del prior y se tratan como raras, que es
el **fallo seguro** — peor sería darlas por comunes y ocultar una rareza real.

**Comprobado end-to-end en emulador**: 448 especies cacheadas en una celda del
norte de California, y las más comunes salen siendo el colibrí de Anna, el
carpintero bellotero y la chara californiana. Es exactamente la avifauna de esa
zona.

### Dos reglas que no se pueden romper

- **Ningún prior vale cero.** Con un cero una especie deja de ser detectable por
  construcción, y las primeras víctimas serían las rarezas: lo más interesante
  que puede pasar con esta app. Se usa un suelo de 0,05.
- **La influencia del contexto es 0,5, no 1.** El prior es una estadística, no
  una verdad. Si mandara del todo, un divagante auténtico quedaría sepultado
  bajo lo que "debería" haber en la zona.

Un fallo de red **no** se cachea como "aquí no hay aves":
`GbifUnavailableException` es deliberadamente distinta de un resumen vacío.
Confundirlas dejaría toda una zona con las especies marcadas como excepcionales
por haber tenido mala cobertura un momento.

---

## Detalles del pipeline de audio

```
AudioRecord(UNPROCESSED, 48kHz, mono, PCM16)
  → ring buffer de 144.000 muestras (3 s)
  → ventana cada 800 ms (solape del 73%)
  → BirdNET: float32[1,144000] → float32[1,6522] LOGITS
  → sigmoide  ← NO softmax: es multi-etiqueta
  → descarte de las 7 clases que no son aves
  → histéresis: 3 ventanas consecutivas para confirmar
  → pantalla
```

Cosas que no son obvias y conviene no "arreglar":

- **`AudioSource.UNPROCESSED` es esencial.** El control automático de ganancia y
  la supresión de ruido del sistema están afinados para voz humana y destrozan
  los cantos agudos. Hay fallback porque no todos los dispositivos lo ofrecen.
- **El `.tflite` acepta PCM crudo.** El espectrograma mel está dentro del grafo.
  No hace falta FFT ni JNI. (Sí haría falta para dibujar un espectrograma en
  pantalla, que es otra cosa.)
- **Sigmoide, no softmax.** En una grabación puede haber dos aves cantando y
  ambas deben poder salir con confianza alta.
- **La histéresis no es cosmética.** Sin ella la lista cambia 1,25 veces por
  segundo y es ilegible; además entrarían en la base de datos detecciones de una
  sola ventana, que casi siempre son ruido.
- **`noCompress += "tflite"`** en `:core:ml:birdnet`. Los modelos se mapean en
  memoria con `openFd`, y eso exige que el APK los guarde sin comprimir.

---

## Notas de build

- **AGP 9 lleva Kotlin integrado.** Aplicar `org.jetbrains.kotlin.android`
  **rompe el build**. `kotlinOptions {}` está obsoleto; se usa
  `kotlin { compilerOptions { … } }`.
- **El plugin del compilador de Compose sigue haciendo falta.** La guía oficial
  de migración a Kotlin integrado no lo menciona, pero sin él falla en cuanto se
  activa `buildFeatures.compose`.
- **Kotlin 2.3.10, no 2.4.x.** KSP (que necesitará Room en Fase 2) no tiene
  versión para 2.4.
- **El artefacto `litert` publica dos líneas incompatibles.** 1.4.2 es la V1
  (API `Interpreter`), 2.1.6 es la V2 (API `CompiledModel`). Se usa la V1.

El APK de debug pesa ~100 MB: 63 MB de modelos más las librerías nativas de
LiteRT para cuatro ABIs. Antes de distribuir nada hay que meter *ABI splits* y
descarga de modelos bajo demanda.

---

## Fuentes de datos

- **GBIF** — ocurrencias, prior geográfico y rareza. Sin API key, sin cláusula
  no comercial. Es el caballo ganador.
- **eBird** — solo taxonomía (`ref/taxonomy/ebird?locale=es`, no necesita key).
  El resto de su API es no comercial.
- **xeno-canto** — audio de referencia. Key obligatoria desde octubre de 2025.

---

## Créditos

Identificación por canto mediante **BirdNET** (Stefan Kahl, Connor Wood,
Maximilian Eibl, Holger Klinck), K. Lisa Yang Center for Conservation
Bioacoustics, Cornell Lab of Ornithology. Modelos bajo CC BY-NC-SA 4.0.

Los parámetros de captura de audio se contrastaron con
[whoBIRD](https://github.com/woheller69/whoBIRD) (GPLv3), que resuelve el mismo
problema en Android. Se ha usado como referencia de lectura, no se ha copiado
código.
