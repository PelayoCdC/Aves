<#
.SYNOPSIS
    Genera los assets del catalogo de especies y el cross-walk de BirdNET.

.DESCRIPTION
    Cruza tres fuentes:
      1. Taxonomia de eBird (API publica, sin key) -> nombres, familia, orden.
      2. taxo_code.txt de BirdNET -> codigo eBird de cada clase del modelo.
      3. labels_es.txt de BirdNET -> etiqueta cruda de cada clase.

    Salida: dos TSV en los assets de :core:database, que la app importa en el
    primer arranque dentro de una unica transaccion.

    POR QUE TSV Y NO UNA BASE DE DATOS PREPOBLADA
    El plan original preveia generar un .db y cargarlo con createFromAsset().
    Se descarto: ese mecanismo exige que el fichero lleve el identityHash exacto
    que Room calcula a partir del esquema, y generarlo fuera de Room es fragil
    (cualquier cambio en una entidad rompe el arranque en el movil del usuario,
    no en el build). Importar ~18.000 filas en una transaccion tarda menos de un
    segundo y falla de forma visible y depurable.

    El cruce es casi 1:1 gracias a taxo_code.txt: BirdNET usa taxonomia eBird.

.EXAMPLE
    pwsh -File scripts/build-catalog.ps1
#>
[CmdletBinding()]
param(
    [string]$Locale = 'es',
    [switch]$RefreshTaxonomy
)

$ErrorActionPreference = 'Stop'

$repoRoot   = Split-Path -Parent $PSScriptRoot
$birdnetDir = Join-Path $repoRoot 'core\ml\birdnet\src\main\assets'
$outDir     = Join-Path $repoRoot 'core\database\src\main\assets\catalog'
$cacheDir   = Join-Path $repoRoot 'build\catalog-cache'
$taxonomyCsv = Join-Path $cacheDir "ebird-taxonomy-$Locale.csv"

New-Item -ItemType Directory -Force -Path $outDir, $cacheDir | Out-Null

# --- 1. Taxonomia de eBird -------------------------------------------------
if ($RefreshTaxonomy -or -not (Test-Path $taxonomyCsv)) {
    Write-Host "Descargando taxonomia de eBird ($Locale)..." -NoNewline
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 300 `
        -Uri "https://api.ebird.org/v2/ref/taxonomy/ebird?locale=$Locale&fmt=csv" `
        -OutFile $taxonomyCsv
    Write-Host (" {0:N1} MB" -f ((Get-Item $taxonomyCsv).Length / 1MB)) -ForegroundColor Green
} else {
    Write-Host "Taxonomia en cache (usa -RefreshTaxonomy para volver a bajarla)" -ForegroundColor DarkGray
}

# Import-Csv y no un split por comas: hay 3.164 lineas con campos
# entrecomillados que contienen comas (nombres como "Herrerillo Canario (La
# Gomera y El Hierro)"). Partir a mano desplaza las columnas en silencio.
$taxonomy = Import-Csv -Path $taxonomyCsv
Write-Host ("Taxonomia: {0:N0} taxones" -f $taxonomy.Count)

$byCode = @{}
$bySciName = @{}
foreach ($row in $taxonomy) {
    $byCode[$row.SPECIES_CODE] = $row
    # Indice secundario por nombre cientifico. Es el respaldo para la deriva
    # taxonomica: BirdNET v2.4 es de 2023 y unos 38 de sus codigos eBird ya no
    # existen, aunque el ave si. Solo se indexan taxones de rango especie para
    # no resolver un nombre a una subespecie o a un hibrido.
    if ($row.CATEGORY -eq "species" -and -not $bySciName.Contains($row.SCIENTIFIC_NAME)) {
        $bySciName[$row.SCIENTIFIC_NAME] = $row
    }
}

# --- 2 y 3. Fuentes de BirdNET ---------------------------------------------
$taxoCodes = @(Get-Content (Join-Path $birdnetDir 'taxo_code.txt') | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
$labels    = @(Get-Content (Join-Path $birdnetDir 'labels_es.txt') -Encoding UTF8 | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })

if ($taxoCodes.Count -ne $labels.Count) {
    throw "Descuadre: taxo_code.txt tiene $($taxoCodes.Count) lineas y labels_es.txt $($labels.Count). No corresponden al mismo modelo."
}
Write-Host ("BirdNET: {0:N0} clases" -f $labels.Count)

# --- Cruce ------------------------------------------------------------------
# --- Excepciones auditadas a mano -------------------------------------------
# Aves cuyo taxon de BirdNET v2.4 ya no existe en la taxonomia actual de eBird
# ni por codigo ni por nombre cientifico, normalmente por agrupaciones
# posteriores. Cada linea es una decision taxonomica consciente, no un parche:
# se anota el motivo para que dentro de dos anos se pueda revisar.
$manualOverrides = @{
    # eBird agrupo el Carbonero Japones dentro de Parus major (gretit1).
    "Parus minor" = "Parus major"
}

function Get-SpeciesId([string]$scientificName) {
    # Identificador canonico interno. Se deriva del nombre cientifico y no del
    # codigo eBird para no atar la clave primaria a una fuente externa: si
    # algun dia se cambia de taxonomia, solo hay que reescribir una columna de
    # mapeo, no migrar las observaciones del usuario.
    return ($scientificName.ToLowerInvariant() -replace '[^a-z0-9]+', '_').Trim('_')
}

$speciesRows = [ordered]@{}
$labelRows   = New-Object System.Collections.Generic.List[string]
$unmapped    = New-Object System.Collections.Generic.List[string]
$recovered   = New-Object System.Collections.Generic.List[string]

for ($i = 0; $i -lt $labels.Count; $i++) {
    $label = $labels[$i]
    $code  = $taxoCodes[$i]
    $sep   = $label.IndexOf('_')
    $sci   = if ($sep -ge 0) { $label.Substring(0, $sep) } else { $label }
    $com   = if ($sep -ge 0) { $label.Substring($sep + 1) } else { $label }

    $entry = $byCode[$code]
    if ($null -eq $entry) {
        # Respaldo por nombre cientifico antes de darla por perdida, y despues
        # la lista de excepciones auditadas a mano.
        $lookupName = if ($manualOverrides.Contains($sci)) { $manualOverrides[$sci] } else { $sci }
        $entry = $bySciName[$lookupName]
        if ($null -ne $entry) { $recovered.Add(("{0}`t{1}`t{2}" -f $i, $code, $label)) }
    }
    if ($null -eq $entry) {
        # Lo que queda son las 11 clases de no-evento (Dog, Engine, Noise, Siren,
        # Gun, Fireworks, Environmental, Power tools y las tres de humano) y unos
        # 90 animales que no son aves: ranas, sapos, grillos, saltamontes,
        # ciervos y monos. BirdNET los incluye a proposito. speciesId queda nulo,
        # que es justo para lo que existe esa columna.
        $unmapped.Add(("{0}`t{1}`t{2}" -f $i, $code, $label))
        $labelRows.Add(("{0}`t`t{1}" -f $i, $label))
        continue
    }

    $speciesId = Get-SpeciesId $entry.SCIENTIFIC_NAME
    $labelRows.Add(("{0}`t{1}`t{2}" -f $i, $speciesId, $label))

    if (-not $speciesRows.Contains($speciesId)) {
        $genus = ($entry.SCIENTIFIC_NAME -split ' ')[0]
        # El nombre comun de eBird va en el idioma pedido; si viniera vacio se
        # usa el de BirdNET, que tambien esta traducido.
        $common = if ([string]::IsNullOrWhiteSpace($entry.COMMON_NAME)) { $com } else { $entry.COMMON_NAME }
        $speciesRows[$speciesId] = ("{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}" -f `
            $speciesId, $entry.SCIENTIFIC_NAME, $genus, $entry.FAMILY_SCI_NAME, `
            $entry.ORDER, $common, $entry.SPECIES_CODE)
    }
}

# --- Salida -----------------------------------------------------------------
$speciesFile = Join-Path $outDir 'species.tsv'
$labelsFile  = Join-Path $outDir 'model_labels_birdnet-v2.4.tsv'

$speciesHeader = "speciesId`tscientificName`tgenus`tfamily`torder`tcommonName`tebirdCode"
$labelsHeader  = "labelIndex`tspeciesId`trawLabel"

Set-Content -Path $speciesFile -Encoding UTF8 -Value (@($speciesHeader) + @($speciesRows.Values))
Set-Content -Path $labelsFile  -Encoding UTF8 -Value (@($labelsHeader) + $labelRows)

Write-Host ''
Write-Host ("  species.tsv                    {0,6:N0} especies" -f $speciesRows.Count) -ForegroundColor Green
Write-Host ("  model_labels_birdnet-v2.4.tsv  {0,6:N0} clases" -f $labelRows.Count) -ForegroundColor Green
Write-Host ("  recuperadas por nombre cientif.{0,6:N0} clases" -f $recovered.Count) -ForegroundColor Green
Write-Host ("  sin mapear                     {0,6:N0} clases" -f $unmapped.Count) -ForegroundColor $(if ($unmapped.Count -gt 50) { 'Yellow' } else { 'DarkGray' })

if ($unmapped.Count -gt 0) {
    $auditFile = Join-Path $cacheDir 'unmapped.tsv'
    Set-Content -Path $auditFile -Encoding UTF8 -Value (@("labelIndex`ttaxoCode`trawLabel") + $unmapped)
    Write-Host ''
    Write-Host 'Clases sin especie canonica (hay que auditarlas a mano):'
    $unmapped | Select-Object -First 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    if ($unmapped.Count -gt 20) { Write-Host "    ... y $($unmapped.Count - 20) mas en $auditFile" -ForegroundColor DarkGray }
}

# Si esto falla, el fichero de etiquetas no corresponde al modelo y TODAS las
# identificaciones saldrian desplazadas. Mejor reventar el build.
if ($labelRows.Count -ne 6522) {
    Write-Host ''
    Write-Host "ERROR: se esperaban 6522 clases y hay $($labelRows.Count)." -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host 'Catalogo generado.' -ForegroundColor Green

# =============================================================================
# Cross-walk del clasificador de IMAGEN (Google AIY birds_V1 v3)
# =============================================================================
#
# El labelmap se publica aparte del modelo y NO requiere cuenta de Kaggle, asi
# que el cruce se puede construir aunque el .tflite todavia no este descargado.
#
# OJO CON EL ORDEN. El CSV trae la columna `id` y NO esta ordenado por ella:
# `background` aparece en la primera fila con id 964. Hay que indexar por la
# columna, nunca por la posicion de la linea. Es exactamente el fallo que la
# version 3 del modelo vino a corregir ("Fixes out-of-order logit to labelmap
# mapping"), asi que repetirlo aqui seria ironico y silencioso: devolveria la
# especie equivocada con toda confianza.

$aiyLabelmap = Join-Path $cacheDir 'aiy_birds_labelmap.csv'
if ($RefreshTaxonomy -or -not (Test-Path $aiyLabelmap)) {
    Write-Host ''
    Write-Host 'Descargando el labelmap del clasificador de imagen...' -NoNewline
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 120 `
        -Uri 'https://www.gstatic.com/aihub/tfhub/labelmaps/aiy_birds_V1_labelmap.csv' `
        -OutFile $aiyLabelmap
    Write-Host ' ok' -ForegroundColor Green
}

$aiyRows = Import-Csv -Path $aiyLabelmap
Write-Host ("Modelo de imagen: {0:N0} clases" -f $aiyRows.Count)

# Indice de nuestro catalogo por speciesId, para el cruce directo.
$catalogIds = @{}
foreach ($id in $speciesRows.Keys) { $catalogIds[$id] = $true }

# Cache de resoluciones de sinonimos: las consultas a GBIF son lentas y el
# resultado no cambia entre ejecuciones.
$synonymCachePath = Join-Path $cacheDir 'gbif-synonyms.json'
$synonymCache = if (Test-Path $synonymCachePath) {
    (Get-Content $synonymCachePath -Raw | ConvertFrom-Json)
} else { $null }
$synonyms = @{}
if ($null -ne $synonymCache) {
    foreach ($p in $synonymCache.PSObject.Properties) { $synonyms[$p.Name] = $p.Value }
}

function Resolve-AcceptedName([string]$name) {
    # El modelo de imagen es de ~2018 y arrastra nombres que la taxonomia ya ha
    # movido de genero. GBIF publica una API de emparejamiento sin clave hecha
    # justo para esto.
    if ($synonyms.ContainsKey($name)) { return $synonyms[$name] }
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 30 `
            -Uri ("https://api.gbif.org/v1/species/match?kingdom=Animalia&name=" + [uri]::EscapeDataString($name))
        $s = if ($r.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($r.Content) } else { $r.Content }
        $j = $s | ConvertFrom-Json
        # Solo se acepta una coincidencia de rango especie y con confianza alta:
        # un emparejamiento dudoso que asigne la especie equivocada es peor que
        # no mapear nada.
        $accepted = if ($j.confidence -ge 90 -and $j.rank -eq 'SPECIES') { $j.species } else { $null }
    } catch {
        $accepted = $null
    }
    $synonyms[$name] = $accepted
    return $accepted
}

$aiyLabelRows = New-Object System.Collections.Generic.List[string]
$aiyDirect = 0; $aiyViaSynonym = 0; $aiyUnmapped = 0; $aiyBackground = 0; $aiySubspecies = 0

# NO se intenta emparejar por epiteto especifico aunque sea unico en el
# catalogo. Se probo y es peligroso: "Vireo gilvus" (vireo gorjeador) tiene como
# unico candidato "Mimus gilvus" (sinsonte tropical). Son familias distintas que
# comparten epiteto por casualidad, y el mapeo etiquetaria un ave como otra con
# toda confianza. Es justo el fallo silencioso contra el que se protege el resto
# del proyecto: preferible dejar 100 clases sin mapear que colar una mal.

foreach ($row in ($aiyRows | Sort-Object { [int]$_.id })) {
    $index = [int]$row.id
    $name = $row.name

    if ($name -eq 'background') {
        # La clase de no-evento del modelo de imagen. speciesId vacio, igual que
        # las 11 de BirdNET.
        $aiyLabelRows.Add(("{0}`t`t{1}" -f $index, $name))
        $aiyBackground++
        continue
    }

    $speciesId = Get-SpeciesId $name
    if ($catalogIds.ContainsKey($speciesId)) {
        $aiyLabelRows.Add(("{0}`t{1}`t{2}" -f $index, $speciesId, $name))
        $aiyDirect++
        continue
    }

    # Trinomios: el modelo distingue subespecies que nuestro catalogo no sigue
    # ("Ardea herodias occidentalis", "Junco hyemalis caniceps"). Plegarlas sobre
    # su especie es una operacion de cadena, no un heuristico: las dos primeras
    # palabras SON el binomio por definicion de la nomenclatura trinomial.
    $parts = $name -split "\s+"
    if ($parts.Count -ge 3) {
        $foldedId = Get-SpeciesId ("{0} {1}" -f $parts[0], $parts[1])
        if ($catalogIds.ContainsKey($foldedId)) {
            $aiyLabelRows.Add(("{0}`t{1}`t{2}" -f $index, $foldedId, $name))
            $aiySubspecies++
            continue
        }
    }

    $accepted = Resolve-AcceptedName $name
    if ($accepted) {
        $acceptedId = Get-SpeciesId $accepted
        if ($catalogIds.ContainsKey($acceptedId)) {
            $aiyLabelRows.Add(("{0}`t{1}`t{2}" -f $index, $acceptedId, $name))
            $aiyViaSynonym++
            continue
        }
    }

    $aiyLabelRows.Add(("{0}`t`t{1}" -f $index, $name))
    $aiyUnmapped++
}

Set-Content -Path $synonymCachePath -Encoding UTF8 -Value ($synonyms | ConvertTo-Json -Depth 3)

$aiyFile = Join-Path $outDir 'model_labels_aiy-birds-v1.tsv'
Set-Content -Path $aiyFile -Encoding UTF8 -Value (@($labelsHeader) + $aiyLabelRows)

Write-Host ''
Write-Host ("  model_labels_aiy-birds-v1.tsv  {0,6:N0} clases" -f $aiyLabelRows.Count) -ForegroundColor Green
Write-Host ("    cruce directo                {0,6:N0}" -f $aiyDirect)
Write-Host ("    subespecie plegada           {0,6:N0}" -f $aiySubspecies) -ForegroundColor Green
Write-Host ("    via sinonimo de GBIF         {0,6:N0}" -f $aiyViaSynonym) -ForegroundColor Green
Write-Host ("    clase de fondo               {0,6:N0}" -f $aiyBackground)
Write-Host ("    sin mapear                   {0,6:N0}" -f $aiyUnmapped) -ForegroundColor $(if ($aiyUnmapped -gt 100) { 'Yellow' } else { 'DarkGray' })

if ($aiyLabelRows.Count -ne 965) {
    Write-Host "ERROR: se esperaban 965 clases en el modelo de imagen y hay $($aiyLabelRows.Count)." -ForegroundColor Red
    exit 1
}
