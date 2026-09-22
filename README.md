# Aves

App Android de identificación de aves por **canto** y por **foto**, con una
**Pokédex** cuyas rarezas se calculan a partir de datos reales de ocurrencia.

Estado: **funcional en los dos flavors.** Escucha en segundo plano con
BirdNET v2.4 (`personal`) o BirdNET+ V3 (`open`, publicable), identificación
por foto (cámara o galería), análisis de grabaciones en cualquier formato que
decodifique el móvil, catálogo de 11.167 especies, prior geo-temporal, rarezas
sobre GBIF, Pokédex filtrada por tu provincia, comunidad o país (o cualquier
otra zona, o Doñana) con las especies que te faltan, medallas de logros,
bandeja de revisión con alternativas, historial de escuchas, fichas con foto y
descripción de Wikipedia, exportación a CSV y tile de ajustes rápidos.

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
los modelos de BirdNET v2.4 son CC BY-NC-SA 4.0 y el catálogo deriva de la
taxonomía de eBird (ver [Licencias](#licencias)).

El orden importa: `build-catalog.ps1` lee las etiquetas que trae el primer
script (`taxo_code.txt`, `labels_es.txt`, `birdnetplus_labels.csv`).

El clasificador de imagen se descarga a mano de Kaggle (pide cuenta) y se deja
en `core/ml/vision/src/main/assets/`; el script lo explica al terminar. Si
falta algo, la app **no se rompe en silencio**: cada pantalla dice qué script
hay que ejecutar.

---

## Licencias — léelo antes de tocar nada

Este proyecto mezcla código propio con modelos de terceros que **no** comparten
su licencia. Es la restricción que más condiciona el diseño.

| Componente | Licencia | ¿Comercial? | Flavor |
|---|---|---|---|
| Código de este repositorio | (a elegir) | Sí | ambos |
| BirdNET GLOBAL 6K v2.4 (audio + meta-modelo) | CC BY-NC-SA 4.0 | **No** | `personal` |
| BirdNET+ V3.0 (audio + geomodelo) | Apache 2.0 | Sí | `open` |
| Google AIY birds_V1 **v3** (imagen) | Apache 2.0 | Sí | ambos |
| GBIF API | sin restricción NC | Sí | ambos |
| eBird taxonomía (`ref/taxonomy`) | sin key; el resto de eBird es NC | — | catálogo |
| Wikipedia (REST summary) | CC BY-SA (texto), varía (imágenes) | Sí, citando | ambos |
| Mapa base (OpenMapTiles / OpenStreetMap, vía GBIF) | ODbL, atribución | Sí | ambos |
| osmdroid | Apache 2.0 | Sí | ambos |

> **Trampa del modelo de imagen.** Hay que usar la **variación 3** de
> `google/aiy/tfLite/vision-classifier-birds-v1` y solo esa. Su changelog dice
> literalmente que la v3 *"Fixes out-of-order logit to labelmap mapping"*: las
> versiones 1 y 2 devuelven la especie equivocada en silencio.

BirdNET es de la K. Lisa Yang Center for Conservation Bioacoustics (Cornell Lab
of Ornithology). **La atribución es requisito de licencia**, por eso las
pantallas de identificación y los ajustes la muestran siempre y no es ocultable.

### Cómo se sostiene esto en el build

Dos *product flavors*, un solo código fuente:

- **`personal`** — enlaza `:core:ml:birdnet` (CC BY-NC-SA). No publicable.
- **`open`** — enlaza `:core:ml:birdnetplus` (Apache 2.0). Publicable, y cubre
  más especies (8.923 frente a 6.419).

```bash
gradlew.bat :app:assemblePersonalDebug
```

```bash
gradlew.bat :app:assembleOpenDebug
```

Cada flavor compila su propio `AudioClassifierProvider.kt`; la elección no
cuesta ni una comprobación en runtime. Y la CI ejecuta
`OpenFlavorLicenseTest`, que comprueba por reflexión que las clases del modelo
no comercial **no están en el classpath** de `open`. No basta con no usarlas:
no deben estar.

---

## Arquitectura

```
app/                    ensamblaje, navegación, flavors, AppContainer
build-logic/            convention plugins (aves.android.library, .compose, ...)
core/
  common/  model/       Kotlin puro, cero Android
  designsystem/         tema M3
  audio/                AudioRecord, ring buffer, gate, espectrograma, remuestreo
  media/                reproductor de recortes (ExoPlayer)
  map/                  mapa: osmdroid + teselas de GBIF
  network/              GBIF y Wikipedia (Ktor)
  database/             Room. KSP aislado aquí
  data/                 repositorios: la frontera de las features
  listening/            el bucle de escucha y el servicio en primer plano
  ml/api/               interfaces. Cero dependencias de runtimes de ML
  ml/birdnet/           BirdNET v2.4 + MData (LiteRT). CC BY-NC-SA
  ml/birdnetplus/       BirdNET+ V3 + geomodelo (ONNX Runtime). Apache 2.0
  ml/vision/            Google AIY birds (LiteRT). Apache 2.0
feature/
  identify/  camera/  review/  pokedex/  settings/
```

El módulo que importa es **`:core:ml:api`**. Define *qué* hace un clasificador,
nunca *cómo*. Tres decisiones concretas lo hacen funcionar:

1. **El modelo declara su formato de audio**, no la app. `AudioCaptureEngine` se
   configura leyendo `requiredSampleRate` y `requiredSampleCount` del
   clasificador. BirdNET+ trabaja a 32 kHz y en ONNX, y meterlo no tocó una
   línea de `:core:audio` ni de las pantallas.
2. **`ModelDescriptor.allowsCommercialUse` es un dato en runtime**, no un
   comentario. Permite el test que falla el build de `open` si se cuela un
   modelo no comercial.
3. **Fuera de `:core:ml` nadie ve un índice de tensor.** La tabla
   `model_labels` traduce (modelo, índice) → especie canónica, y cada modelo
   es una tabla de mapeo más.

Regla que no se debe romper: si algún día hay que añadir LiteRT u ONNX a
`:core:ml:api`, es señal de que la abstracción se está rompiendo.

### La escucha vive en un servicio

`ListeningService` (primer plano, tipo `microphone`) ejecuta `ListeningEngine`
fuera del ciclo de vida de cualquier pantalla: apagar la pantalla o cambiar de
app no corta la identificación, que es el caso de uso que justifica la app.
El estado se expone como un `StateFlow` estático y no por binder (solo hay un
micrófono; ver el KDoc de la clase). Corte automático configurable, con tope
de 8 h. Nunca `START_STICKY`: grabar sin que el usuario lo pidiera es lo peor
que podría hacer esta app.

Lo que el servicio no puede construir solo (clasificador del flavor,
repositorios, ajustes, ubicación) se lo da la `Application` por
`ListeningDependencies`.

---

## El pipeline de audio

```
AudioRecord(UNPROCESSED si el sistema lo declara; si no, VOICE_RECOGNITION)
  → ring buffer de 3 s (el tamaño lo dice el modelo)
  → ventana cada 800 ms (solape del 73%)
  → se descarta si la propia app está reproduciendo un recorte
  → gate de energía (-60 dBFS): en el campo casi nunca canta nada
  → clasificador (el del flavor) + prior geo-temporal (peso ajustable)
  → histéresis: 3 ventanas consecutivas por encima de 0,35 para confirmar
  → persistencia: una fila por (especie, sesión), la mejor puntuación
  → pantalla: espectrograma, nivel, "oyendo… ¿X?", confirmadas
```

Cosas que no son obvias y conviene no "arreglar":

- **`UNPROCESSED` solo si `AudioManager` lo declara.** Un dispositivo que no lo
  implementa lo acepta igual y aplica DEFAULT por debajo (AGC y supresión de
  ruido afinados para voz, que destrozan los cantos agudos), mientras la app
  cree estar leyendo audio crudo.
- **Entrada normalizada a [-1, 1] para los dos modelos.** BirdNET v2.4 es
  invariante a la escala (verificado ejecutando el modelo real, ver
  `InputScalingRegressionTest`). BirdNET+ no está verificado y ese es justo el
  motivo: [-1, 1] es la convención de cualquier modelo de audio en PyTorch, y
  es la elección que no puede salir mal. `BirdNetPlusInputScalingTest` es su
  gemelo y dirá si la invariancia se cumple.
- **Sigmoide, no softmax** en BirdNET v2.4. Es multi-etiqueta: dos aves pueden
  cantar a la vez. BirdNET+ ya devuelve probabilidades.
- **El umbral de confirmación es 0,35**, el que publica el propio equipo de
  BirdNET (`defaultConfidenceThreshold`). El 0,65 original se eligió a ojo y en
  la primera prueba de campo no confirmó nada. Se puede cambiar en Ajustes.
- **Top-K 8, no 3.** El estabilizador necesita ver a una especie en ventanas
  consecutivas; con dos aves fuertes, la tercera se caía del top-3.
- **Remuestrear hacia abajo filtra antes de decimar.** Todo fichero normal
  (44,1/48 kHz) baja a 32 kHz en `open`; sin paso-bajo, lo que hay por encima
  de 16 kHz se pliega sobre la banda donde cantan las aves.
- **`noCompress` para `tflite` y `onnx`.** Los modelos se mapean en memoria.
  El ONNX se copia una vez a `noBackupFilesDir` (con el tamaño en el nombre,
  para que un modelo actualizado sustituya la copia).

---

## El catálogo y los cross-walks

El catálogo es **la taxonomía de eBird entera** (11.167 especies), no las
etiquetas de un modelo. Cada modelo es una tabla de mapeo aparte, generada por
`scripts/build-catalog.ps1`:

| Cross-walk | clases | sin mapear (correcto) |
|---|---|---|
| BirdNET v2.4 | 6.522 | 101: 11 no-eventos + 90 animales que no son aves |
| Google AIY birds_V1 | 965 | 1: `background` |
| BirdNET+ V3 | 9.789 | 866: anfibios, mamíferos e insectos |

**Ni un ave sin mapear en ninguno.** Las subespecies y los *slash* se resuelven
siguiendo `REPORT_AS` hasta rango especie, y 28 renombres de género de
Clements/eBird 2022-2024 (*Bubulcus ibis* → *Ardea ibis*, *Sylvia communis* →
*Curruca communis*, los cormoranes a *Urile*/*Nannopterum*…) viven en una lista
de excepciones auditadas contra la taxonomía, con el motivo anotado. Nunca se
empareja por epíteto: *Vireo gilvus* y *Mimus gilvus* son familias distintas
que lo comparten por casualidad.

`CatalogSeeder` compara cada tabla con su TSV y reimporta lo que no cuadre, lo
que cubre las actualizaciones de taxonomía y los modelos nuevos en instalaciones
existentes. Importar ~30.000 filas cuesta unos segundos, una vez, en segundo
plano. No hay base prepoblada con `createFromAsset()`: exige el `identityHash`
exacto de Room y falla en el móvil del usuario, no en el build.

El total de la Pokédex es lo que **algún modelo enlazado** puede identificar,
no las 11.167: prometer huecos imposibles de llenar no tiene gracia.

### La regla que sostiene la Pokédex

**Una observación por especie y sesión**, con la mejor puntuación alcanzada. La
regla vive en `ObservationDao.recordBestInSession` como transacción atómica y
en un índice único. Las decisiones del usuario mandan sobre el modelo: una
detección mejor actualiza la puntuación pero **no resucita** algo descartado.

Y nada entra en la colección sin pasar por la bandeja de revisión, con el
recorte de audio que provocó la detección y los otros candidatos de la misma
ventana ("¿era otra?"). La excepción es la foto: el usuario ya eligió el
candidato mirando al ave.

---

## Contexto, rarezas y ubicación

Hay **dos priors** por flavor tras la misma interfaz `ContextPriorProvider`,
con GBIF de respaldo:

- **`personal`**: el meta-modelo `MData` de BirdNET v2.4.
- **`open`**: el geomodelo de BirdNET+ V3, también Apache 2.0.

Se prefieren a GBIF porque se entrenaron *junto* a su clasificador. Ambos
combinan la semana actual con el máximo anual (para no perder migratorias
adelantadas) y **ningún prior vale cero**: con un cero, una especie deja de ser
detectable por construcción, y las primeras víctimas serían las rarezas.

La **rareza** sale siempre de GBIF: cuota de la especie sobre las observaciones
de aves de la celda (geohash-4, ~40×20 km) en ese mes, con cortes logarítmicos.
Se cachea mes a mes (el actual primero, en un ámbito que sobrevive a la
escucha) y se congela en cada observación. **Sin datos no se inventa nada**:
`RarityTier.UNKNOWN` existe para eso y se rellena cuando llegan los datos de
*su* lugar y *su* mes. La primera versión usaba "excepcional" como valor por
defecto y cada gorrión sin GPS salía dorado.

La ubicación es aproximada (permiso *coarse*, una lectura de red al empezar
cada escucha con tope de 8 s, y luego la celda de 40 km). Se puede apagar del
todo en Ajustes.

### La Pokédex por zona

No hay cuentas, así que "tu zona" se **detecta**: la ubicación (redondeada a un
decimal, ~10 km) se pasa al geocodificador inverso de GBIF, que devuelve los
identificadores GADM de país, región y provincia. Sin ubicación se usa el país
de la red móvil, la SIM o el idioma, en ese orden. Se guarda en Room
(`pokedex_preferences`) y no se vuelve a preguntar salvo que el usuario pulse
"Actualizar mi zona", o que la primera vez solo saliera el país (la Pokédex se
abrió antes de conceder la ubicación): entonces se reintenta al abrirla, y el
filtro se afina solo a la provincia. Un intento fallido nunca borra lo que ya
se sabía (`HomePlace.merge`).

La lista de aves de cada zona son las especies con **al menos 5 registros** en
GBIF dentro de ese `gadmGid` o país (`ChecklistRepository`, caché de 90 días).
El umbral quita las divagantes de un solo día, que convertirían la lista de
León en la de media Europa. Se cruza con lo que los modelos enlazados saben
identificar, y los nombres antiguos de GBIF pasan por `GbifSpeciesNames.ALIASES`
(*Bubulcus ibis* → *Ardea ibis*…).

Por defecto se filtra por el nivel más fino conocido y se enseñan las que
faltan, con lo frecuentes que son *ahí*. Lo que se vio fuera de la zona no se
pierde: se avisa de cuántas especies quedan ocultas. Sin red la primera vez, la
colección se enseña entera en vez de vacía. En España el nivel 1 de GADM se
llama por su nombre: "comunidad autónoma".

**Cualquier otra zona.** El menú de zona lleva a un navegador de dos niveles
(las comunidades del país → sus provincias, vía
`GET /v1/geocode/gadm/{id}/subdivisions`) y a una lista de **lugares
destacados** (`NotablePlaces`: Doñana, delta del Ebro, Monfragüe, Picos de
Europa… 22 en total) definidos como rectángulos WKT en sentido antihorario, que
es lo que acepta el filtro `geometry` de GBIF. La zona elegida se guarda
(`pokedex_preferences.customZone*`, Room v4) y manda sobre la detectada hasta
"Volver a mi zona".

### El mapa

Lo que se echaba en falta de eBird, sin eBird: sus mapas son de licencia no
comercial y con clave, pero GBIF sirve sin clave dos cosas que juntas dan un
mapa de rango real (`core/map`, `GbifTiles`): un **mapa base** (OpenMapTiles
sobre OpenStreetMap, `tile.gbif.org`) y las **teselas de densidad de registros**
de cualquier taxon (`api.gbif.org/v2/map`), en hexagonos, a cualquier zoom. Se
pintan con **osmdroid** (Apache 2.0, sin SDK de Google ni facturacion),
envuelto en un composable (`BirdMap`), con los avistamientos del usuario como
puntos de color por origen (verde visto, naranja foto, morado canto).

Hay dos mapas: el de la coleccion (icono en la Pokedex: todos tus avistamientos
con coordenadas y, como capa, donde se registran aves) y el de cada especie (una
tarjeta en la ficha que se amplia: donde se registra en el mundo y donde la has
visto tu). La clave de GBIF de la especie se pide por nombre cientifico la
primera vez (`species/match`, solo si casa a nivel de especie) y se guarda en
`species.gbifSpeciesKey`, que el catalogo traia vacio.

Privacidad: cada tesela pedida dice a GBIF que zona se esta mirando (y, en la
ficha de una especie, cual). Teselas cacheadas en la cache de la app, con tope
de 120 MB. Atribucion obligatoria en pantalla.

### Logros

Medallas de hierro a diamante por la parte de una lista que tengas en la
colección: **Coleccionista** (todo lo que el modelo sabe identificar, escala
corta: diamante al 20 %), cada nivel de tu zona, cada lugar destacado y cada
familia con al menos cinco especies (escala larga: 5 / 15 / 30 / 50 / 75 /
95 %). Se calculan al vuelo de las mismas listas que usa el filtro, así que no
hay tabla de logros que pueda desfasarse; al abrir la pantalla se bajan las
listas que falten. Cuentan las especies de la colección que están en la lista
de la zona, se hayan visto donde se hayan visto. Cada medalla se dibuja
distinta (`MedalArt`): cinta, motivo y puntas salen del identificador, y el
metal del progreso.

---

## Ficheros de audio y fotos de la galería

`AudioFileDecoder` abre cualquier cosa que decodifique `MediaCodec` (WAV, MP3,
M4A/AAC, OGG, Opus, FLAC, AMR/3GP), mezcla a mono y remuestrea con un FIR
antialias al vuelo. Tope de 5 minutos por fichero; si se corta, se dice. Los
WAV PCM siguen por el decodificador propio, que no depende del códec del
fabricante.

Al elegir un fichero se pregunta **dónde y cuándo se grabó** antes de
analizarlo (`PlacePickerDialog`: ubicación actual, un lugar buscado por nombre
con el `Geocoder` del sistema, coordenadas a mano, o ninguno). Con lugar, el
prior y la rareza se aplican como en una escucha, para ese sitio y esa fecha;
sin él, el análisis va a ciegas. El análisis enseña en qué fase está
(modelo, decodificación, ventanas con porcentaje, pistas) y **solo las especies
seguras**: dos ventanas por encima del umbral, o una muy rotunda
(`FileAnalysisAccumulator`). Sin porcentajes en pantalla; la confianza sigue
en la base de datos. Lo mismo en la escucha en directo, la cámara y Revisar.

**Una pista por especie.** De cada especie encontrada se monta una pista con
solo sus tramos (`SpeciesTrackBuilder`), limpiada con una resta espectral del
ruido de fondo y un foco en la banda del canto (`SpectralCleaner`, STFT de
1024 con raíz de Hann) y normalizada en volumen. Se puede escuchar en la
pantalla y, al guardar, es el recorte de la observación: en la ficha se oye
solo ese ave. Lo que NO hace: separar dos aves que cantan a la vez en la misma
banda; eso pide un modelo de separación de fuentes.

La galería usa el selector de fotos del sistema (`PickVisualMedia`), que **no
pide ningún permiso**. Se respeta la orientación EXIF y la fecha es la de la
foto, no la de hoy. Como una foto de la galería puede ser de cualquier sitio,
va sin ubicación hasta que el usuario dice dónde fue (chip "¿Dónde fue?" junto
a los candidatos); entonces se vuelve a clasificar la misma imagen con ese
contexto. Se analiza el cuadrado central. Los candidatos salen con su nombre
en castellano (`ImageCandidateNamer`, vía `model_labels` y el catálogo) y
**tocar uno no guarda nada**: abre la ficha con la foto al lado de la de
Wikipedia y pregunta "¿es esta?"; solo al confirmar se anota el avistamiento.
Desde Revisar, el nombre de cada detección abre también la ficha.

### Vista, fotografiada, oída

Cada tarjeta de la colección lleva tres iconos fijos (`AvesIcons`): ojo para lo
anotado a mano, cámara para lo identificado por foto y clave de sol para lo
detectado por canto. El que no se tiene **se apaga en vez de desaparecer**: el
hueco es el mensaje. Salen de `CaptureSources`, que cuenta los avistamientos
confirmados por origen. En la ficha, los mismos tres son filtros de la lista
("Fotos · 2", "Cantos · 14"), las fotos tienen su tira y un visor a pantalla
completa con zoom, y cada canto se reproduce desde su fila.

La foto se guarda en dos tamaños (`PhotoStorage`): la miniatura de 224 px que
vio el modelo, que es la ruta que anota la observación, y en `photos/full/` el
encuadre entero a 1.600 px para mirarla. La entera se localiza por convención,
sin columna nueva: una observación antigua o restaurada de una copia enseña su
miniatura en vez de un hueco. Borrar un avistamiento borra también su foto o su
recorte.

---

## Ajustes que hacen algo

`AvesSettings` (DataStore) llega a la escucha en cada arranque vía
`ListeningDependencies.currentSettings()`: peso del contexto, umbral de
confirmación, corte automático y modo sin ubicación. La cámara lee los mismos.
Los valores de fábrica son los del motor (0,5 / 0,35 / 1 h).

---

## Copia de seguridad

`clips/` y `photos/full/` quedan fuera de la copia en la nube (`res/xml/backup_rules.xml` y
`data_extraction_rules.xml`): Auto Backup tiene una cuota de 25 MB y con los
recortes dentro fallaba **entera**, colección incluida. La base de datos, las
miniaturas de las fotos (224 px, ~15 KB) y los ajustes caben de sobra. Los modelos copiados a disco viven
en `noBackupFilesDir`.

---

## Tests

```bash
gradlew.bat test
```

Unitarios en la JVM: aritmética del prior, estabilizador, remuestreo, FFT,
lógica de la bandeja, estadísticas de la colección, clientes de red con
`MockEngine`, ajustes, cross-walk (con DAOs falsos). Los instrumentados
(`connectedDebugAndroidTest`) ejecutan los modelos reales: invariancia de
escala, sentido común del geomodelo (un petirrojo en Madrid pesa más que un
pingüino), siembra del catálogo y política de escritura. Necesitan los assets,
que no están en el repositorio.

---

## Notas de build

- **AGP 9 lleva Kotlin integrado.** Aplicar `org.jetbrains.kotlin.android`
  **rompe el build**. El plugin del compilador de Compose sigue haciendo falta
  aunque la guía de migración no lo mencione.
- **Kotlin 2.3.10, no 2.4.x.** KSP (Room) no tiene versión para 2.4.
- **El artefacto `litert` publica dos líneas incompatibles.** 1.4.2 es la V1
  (API `Interpreter`), 2.1.6 es la V2 (API `CompiledModel`). Se usa la V1.
- **Coil 3 no trae red.** Sin `coil-network-okhttp` una URL https no carga nada,
  en silencio.
- ABI splits activados: un APK por arquitectura más el universal. Con solo el
  FP16 de BirdNET en assets, el `personal` arm64 ronda los 70 MB.

---

## Fuentes de datos

- **GBIF** — ocurrencias, rareza, prior de respaldo, listas por zona, GADM,
  geocodificación inversa, mapa base y teselas de densidad. Sin API key, sin cláusula
  no comercial.
- **eBird** — solo taxonomía (`ref/taxonomy/ebird?locale=es`, sin key). El
  resto de su API es no comercial.
- **Wikipedia** — foto y descripción de cada especie, una consulta por especie
  y guardada. El `User-Agent` identifica a la app, nunca al usuario.

---

## Créditos

Identificación por canto mediante **BirdNET** (Stefan Kahl, Connor Wood,
Maximilian Eibl, Holger Klinck) y **BirdNET+**, K. Lisa Yang Center for
Conservation Bioacoustics, Cornell Lab of Ornithology. Identificación por
imagen con **Google AIY Vision Classifier birds_V1**.

El mapa base es de [OpenMapTiles](https://openmaptiles.org/) sobre datos de
[OpenStreetMap](https://www.openstreetmap.org/copyright) (ODbL), servido por
GBIF; el mapa se pinta con [osmdroid](https://github.com/osmdroid/osmdroid)
(Apache 2.0).

El icono de la app es un vector propio (`ic_launcher_foreground.xml`,
previsualizable con `scripts/render-icon.ps1`). El icono de la clave de sol es
`music-clef-treble` de
[Material Design Icons](https://pictogrammers.com/library/mdi/) (Pictogrammers,
Apache 2.0).

Los parámetros de captura de audio se contrastaron con
[whoBIRD](https://github.com/woheller69/whoBIRD) (GPLv3), que resuelve el mismo
problema en Android. Se ha usado como referencia de lectura, no se ha copiado
código.
