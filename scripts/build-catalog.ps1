<#
.SYNOPSIS
    Genera los assets del catalogo de especies y los cross-walks de los modelos.

.DESCRIPTION
    Cruza estas fuentes:
      1. Taxonomia de eBird (API publica, sin key) -> el catalogo entero: las
         ~11.200 especies del planeta, con nombre en castellano, familia y orden.
      2. taxo_code.txt + labels_es.txt de BirdNET v2.4 -> cross-walk del
         clasificador de audio del flavor `personal`.
      3. Labelmap de Google AIY birds_V1 -> cross-walk del clasificador de imagen.
      4. Etiquetas de BirdNET+ V3 -> cross-walk del clasificador de audio del
         flavor `open`.

    Salida: TSVs en los assets de :core:database, que la app importa en el
    primer arranque (y vuelve a importar si cambian de tamano).

    POR QUE EL CATALOGO ES LA TAXONOMIA ENTERA Y NO LAS ETIQUETAS DE UN MODELO
    Antes el catalogo se derivaba de las 6.522 clases de BirdNET v2.4. Eso
    funcionaba con un solo modelo y se rompio con el segundo: BirdNET+ conoce
    8.923 aves y unas 2.500 no tenian ficha. Con la taxonomia completa como
    catalogo, cada modelo es solo una tabla de mapeo mas, que es exactamente lo
    que promete la arquitectura.

    POR QUE TSV Y NO UNA BASE DE DATOS PREPOBLADA
    createFromAsset() exige que el fichero lleve el identityHash exacto que Room
    calcula a partir del esquema, y generarlo fuera de Room es fragil: cualquier
    cambio en una entidad rompe el arranque en el movil del usuario, no en el
    build. Importar ~30.000 filas en una transaccion tarda unos segundos y falla
    de forma visible y depurable.

.EXAMPLE
    pwsh -File scripts/build-catalog.ps1
#>
[CmdletBinding()]
param(
    [string]$Locale = 'es',
    [switch]$RefreshTaxonomy
)

$ErrorActionPreference = 'Stop'

$repoRoot    = Split-Path -Parent $PSScriptRoot
$birdnetDir  = Join-Path $repoRoot (Join-Path 'core' (Join-Path 'ml' (Join-Path 'birdnet' (Join-Path 'src' (Join-Path 'main' 'assets')))))
$plusDir     = Join-Path $repoRoot (Join-Path 'core' (Join-Path 'ml' (Join-Path 'birdnetplus' (Join-Path 'src' (Join-Path 'main' 'assets')))))
$outDir      = Join-Path $repoRoot (Join-Path 'core' (Join-Path 'database' (Join-Path 'src' (Join-Path 'main' (Join-Path 'assets' 'catalog')))))
$cacheDir    = Join-Path $repoRoot (Join-Path 'build' 'catalog-cache')
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

# Import-Csv y no un split por comas: hay miles de lineas con campos
# entrecomillados que contienen comas (nombres como "Herrerillo Canario (La
# Gomera y El Hierro)"). Partir a mano desplaza las columnas en silencio.
$taxonomy = Import-Csv -Path $taxonomyCsv
Write-Host ("Taxonomia: {0:N0} taxones" -f $taxonomy.Count)

function Get-SpeciesId([string]$scientificName) {
    # Identificador canonico interno. Se deriva del nombre cientifico y no del
    # codigo eBird para no atar la clave primaria a una fuente externa: si
    # algun dia se cambia de taxonomia, solo hay que reescribir una columna de
    # mapeo, no migrar las observaciones del usuario.
    return ($scientificName.ToLowerInvariant() -replace '[^a-z0-9]+', '_').Trim('_')
}

$byCode = @{}
$bySciName = @{}
foreach ($row in $taxonomy) {
    $byCode[$row.SPECIES_CODE] = $row
    # Solo se indexan taxones de rango especie por nombre: resolver un nombre a
    # una subespecie, a un hibrido o a un "spuh" seria un error.
    if ($row.CATEGORY -eq 'species' -and -not $bySciName.Contains($row.SCIENTIFIC_NAME)) {
        $bySciName[$row.SCIENTIFIC_NAME] = $row
    }
}

function Resolve-SpeciesRow($row) {
    # Los modelos a veces apuntan a un taxon que no es una especie (una
    # subespecie identificable `issf`, una forma, un "slash"). eBird dice en
    # REPORT_AS a que especie se reporta cada uno de esos taxones; se sigue esa
    # cadena hasta llegar a rango especie.
    $current = $row
    $hops = 0
    while ($null -ne $current -and $current.CATEGORY -ne 'species' -and $hops -lt 4) {
        if ([string]::IsNullOrWhiteSpace($current.REPORT_AS)) { return $null }
        $current = $byCode[$current.REPORT_AS]
        $hops++
    }
    if ($null -ne $current -and $current.CATEGORY -eq 'species') { return $current }
    return $null
}

# --- Catalogo: todas las especies ------------------------------------------
$speciesRows = [ordered]@{}
foreach ($row in $taxonomy) {
    if ($row.CATEGORY -ne 'species') { continue }
    $speciesId = Get-SpeciesId $row.SCIENTIFIC_NAME
    if ($speciesRows.Contains($speciesId)) { continue }
    $genus = ($row.SCIENTIFIC_NAME -split ' ')[0]
    $common = if ([string]::IsNullOrWhiteSpace($row.COMMON_NAME)) { $row.SCIENTIFIC_NAME } else { $row.COMMON_NAME }
    $speciesRows[$speciesId] = ("{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}" -f `
        $speciesId, $row.SCIENTIFIC_NAME, $genus, $row.FAMILY_SCI_NAME, `
        $row.ORDER, $common, $row.SPECIES_CODE)
}
Write-Host ("Catalogo: {0:N0} especies" -f $speciesRows.Count)

$catalogIds = @{}
foreach ($id in $speciesRows.Keys) { $catalogIds[$id] = $true }

# --- Excepciones auditadas a mano -------------------------------------------
# Taxones de los modelos que ya no existen en la taxonomia actual de eBird ni
# por codigo ni por nombre cientifico, normalmente por agrupaciones posteriores.
# Cada linea es una decision taxonomica consciente, no un parche.
$manualOverrides = @{
    # eBird agrupo el Carbonero Japones dentro de Parus major (gretit1).
    "Parus minor" = "Parus major"

    # Cambios de genero en Clements/eBird 2022-2024. Es el MISMO taxon con otro
    # nombre, no un epiteto que coincide por casualidad: cada uno se ha
    # comprobado contra la taxonomia antes de anotarlo aqui.
    "Bubulcus ibis" = "Ardea ibis"
    "Charadrius mongolus" = "Anarhynchus mongolus"
    "Charadrius montanus" = "Anarhynchus montanus"
    "Charadrius wilsonia" = "Anarhynchus wilsonia"
    "Charadrius nivosus" = "Anarhynchus nivosus"
    "Charadrius dubius" = "Thinornis dubius"
    "Phaeomyias murina" = "Nesotriccus murinus"
    "Poospizopsis hypocondria" = "Poospizopsis hypochondria"   # grafia corregida en eBird
    "Psilorhinus morio" = "Cyanocorax morio"
    "Calocitta formosa" = "Cyanocorax formosus"
    "Calocitta colliei" = "Cyanocorax colliei"
    "Euphonia elegantissima" = "Chlorophonia elegantissima"
    "Phalacrocorax pelagicus" = "Urile pelagicus"
    "Phalacrocorax penicillatus" = "Urile penicillatus"
    "Phalacrocorax auritus" = "Nannopterum auritum"
    "Phalacrocorax brasilianus" = "Nannopterum brasilianum"
    "Sylvia communis" = "Curruca communis"
    "Regulus calendula" = "Corthylio calendula"
    "Ixobrychus exilis" = "Botaurus exilis"
    "Milvago chimachima" = "Daptrius chimachima"
    "Accipiter cooperii" = "Astur cooperii"
    "Haliaeetus vocifer" = "Icthyophaga vocifer"
    "Haliaeetus leucogaster" = "Icthyophaga leucogaster"
    "Amazilia violiceps" = "Ramosomyia violiceps"
    "Lonchura oryzivora" = "Padda oryzivora"

    # Agrupaciones (lumps): el taxon del modelo dejo de ser especie y se
    # reporta como la especie que lo absorbio.
    "Corvus caurinus" = "Corvus brachyrhynchos"          # Cuervo del Noroeste -> Cuervo Americano (2020)
    "Empidonax occidentalis" = "Empidonax difficilis"     # Mosquero Cordillerano -> del Pacifico (2023)

    # Division (split): "Accipiter gentilis" se partio en Astur gentilis
    # (Euroasiatico) y Astur atricapillus (Norteamericano). El nominal conserva
    # el epiteto y es el que se toma; una foto americana saldra como euroasiatico.
    "Accipiter gentilis" = "Astur gentilis"
}

# --- Sinonimos via GBIF ------------------------------------------------------
# Cache de resoluciones: las consultas a GBIF son lentas y el resultado no
# cambia entre ejecuciones.
$synonymCachePath = Join-Path $cacheDir 'gbif-synonyms.json'
$synonyms = @{}
if (Test-Path $synonymCachePath) {
    $synonymCache = (Get-Content $synonymCachePath -Raw | ConvertFrom-Json)
    foreach ($p in $synonymCache.PSObject.Properties) { $synonyms[$p.Name] = $p.Value }
}

function Resolve-AcceptedName([string]$name) {
    # Los modelos arrastran nombres que la taxonomia ya ha movido de genero.
    # GBIF publica una API de emparejamiento sin clave hecha justo para esto.
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

# NO se intenta emparejar por epiteto especifico aunque sea unico en el
# catalogo. Se probo y es peligroso: "Vireo gilvus" (vireo gorjeador) tiene como
# unico candidato "Mimus gilvus" (sinsonte tropical). Son familias distintas que
# comparten epiteto por casualidad. Preferible dejar clases sin mapear que colar
# una mal.
function Resolve-ByName([string]$name) {
    # Devuelve un par (speciesId, via) o $null.
    $direct = Get-SpeciesId $name
    if ($catalogIds.ContainsKey($direct)) { return @($direct, 'directo') }

    if ($manualOverrides.Contains($name)) {
        $overridden = Get-SpeciesId $manualOverrides[$name]
        if ($catalogIds.ContainsKey($overridden)) { return @($overridden, 'manual') }
    }

    # Trinomios: las dos primeras palabras SON el binomio por definicion de la
    # nomenclatura, asi que plegar una subespecie sobre su especie es una
    # operacion de cadena, no un heuristico.
    $parts = $name -split '\s+'
    if ($parts.Count -ge 3) {
        $folded = Get-SpeciesId ("{0} {1}" -f $parts[0], $parts[1])
        if ($catalogIds.ContainsKey($folded)) { return @($folded, 'subespecie') }
    }

    $accepted = Resolve-AcceptedName $name
    if ($accepted) {
        $acceptedId = Get-SpeciesId $accepted
        if ($catalogIds.ContainsKey($acceptedId)) { return @($acceptedId, 'sinonimo') }
    }
    return $null
}

$labelsHeader = "labelIndex`tspeciesId`trawLabel"

function Write-Summary([string]$file, [hashtable]$stats) {
    Write-Host ("  {0,-38} {1,6:N0} clases" -f (Split-Path -Leaf $file), $stats['total']) -ForegroundColor Green
    foreach ($key in @('directo', 'codigo', 'subespecie', 'sinonimo', 'manual', 'no-ave', 'sin mapear')) {
        if ($stats.ContainsKey($key) -and $stats[$key] -gt 0) {
            $color = if ($key -eq 'sin mapear') { 'Yellow' } else { 'DarkGray' }
            Write-Host ("      {0,-20} {1,6:N0}" -f $key, $stats[$key]) -ForegroundColor $color
        }
    }
}

# =============================================================================
# Cross-walk 1: BirdNET v2.4 (audio, flavor personal)
# =============================================================================
$taxoCodes = @(Get-Content (Join-Path $birdnetDir 'taxo_code.txt') | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
$labels    = @(Get-Content (Join-Path $birdnetDir 'labels_es.txt') -Encoding UTF8 | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })

if ($taxoCodes.Count -ne $labels.Count) {
    throw "Descuadre: taxo_code.txt tiene $($taxoCodes.Count) lineas y labels_es.txt $($labels.Count). No corresponden al mismo modelo."
}

$birdnetRows = New-Object System.Collections.Generic.List[string]
$birdnetStats = @{ total = 0 }
$unmapped = New-Object System.Collections.Generic.List[string]

for ($i = 0; $i -lt $labels.Count; $i++) {
    $label = $labels[$i]
    $code  = $taxoCodes[$i]
    $sep   = $label.IndexOf('_')
    $sci   = if ($sep -ge 0) { $label.Substring(0, $sep) } else { $label }
    $birdnetStats['total']++

    # Los codigos de BirdNET para no-aves son identificadores internos
    # `t-XXXXXXXX`; los de aves son codigos eBird. Y las 11 clases de no-evento
    # (perro, motor, sirena...) no tienen codigo eBird valido.
    $resolved = $null
    $via = $null
    if (-not $code.StartsWith('t-')) {
        $entry = Resolve-SpeciesRow $byCode[$code]
        if ($null -ne $entry) {
            $resolved = Get-SpeciesId $entry.SCIENTIFIC_NAME
            $via = 'codigo'
        } else {
            # Respaldo por nombre: BirdNET v2.4 es de 2023 y unos 38 de sus
            # codigos eBird ya no existen, aunque el ave si.
            $pair = Resolve-ByName $sci
            if ($null -ne $pair) { $resolved = $pair[0]; $via = $pair[1] }
        }
    } else {
        $via = 'no-ave'
    }

    if ($null -ne $resolved) {
        $birdnetRows.Add(("{0}`t{1}`t{2}" -f $i, $resolved, $label))
    } else {
        $birdnetRows.Add(("{0}`t`t{1}" -f $i, $label))
        if ($via -ne 'no-ave') { $via = 'sin mapear'; $unmapped.Add(("{0}`t{1}`t{2}" -f $i, $code, $label)) }
    }
    $birdnetStats[$via] = 1 + $(if ($birdnetStats.ContainsKey($via)) { $birdnetStats[$via] } else { 0 })
}

# =============================================================================
# Cross-walk 2: Google AIY birds_V1 v3 (imagen, ambos flavors)
# =============================================================================
# OJO CON EL ORDEN. El CSV trae la columna `id` y NO esta ordenado por ella:
# `background` aparece en la primera fila con id 964. Hay que indexar por la
# columna, nunca por la posicion de la linea. Es el fallo que la version 3 del
# modelo vino a corregir ("Fixes out-of-order logit to labelmap mapping").
$aiyLabelmap = Join-Path $cacheDir 'aiy_birds_labelmap.csv'
if ($RefreshTaxonomy -or -not (Test-Path $aiyLabelmap)) {
    Write-Host 'Descargando el labelmap del clasificador de imagen...' -NoNewline
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 120 `
        -Uri 'https://www.gstatic.com/aihub/tfhub/labelmaps/aiy_birds_V1_labelmap.csv' `
        -OutFile $aiyLabelmap
    Write-Host ' ok' -ForegroundColor Green
}

$aiyRows = New-Object System.Collections.Generic.List[string]
$aiyStats = @{ total = 0 }
foreach ($row in (Import-Csv -Path $aiyLabelmap | Sort-Object { [int]$_.id })) {
    $index = [int]$row.id
    $name = $row.name
    $aiyStats['total']++

    if ($name -eq 'background') {
        $aiyRows.Add(("{0}`t`t{1}" -f $index, $name))
        $aiyStats['no-ave'] = 1 + $(if ($aiyStats.ContainsKey('no-ave')) { $aiyStats['no-ave'] } else { 0 })
        continue
    }

    $pair = Resolve-ByName $name
    if ($null -ne $pair) {
        $aiyRows.Add(("{0}`t{1}`t{2}" -f $index, $pair[0], $name))
        $via = $pair[1]
    } else {
        $aiyRows.Add(("{0}`t`t{1}" -f $index, $name))
        $via = 'sin mapear'
    }
    $aiyStats[$via] = 1 + $(if ($aiyStats.ContainsKey($via)) { $aiyStats[$via] } else { 0 })
}

# =============================================================================
# Cross-walk 3: BirdNET+ V3 (audio, flavor open)
# =============================================================================
# Formato: idx;id;sci_name;com_name;class;order. La columna `class` separa las
# aves de los anfibios, mamiferos e insectos que el modelo incluye a proposito.
$plusLabelsPath = Join-Path $plusDir 'birdnetplus_labels.csv'
$plusRows = New-Object System.Collections.Generic.List[string]
$plusStats = @{ total = 0 }
if (Test-Path $plusLabelsPath) {
    $plusLines = Get-Content $plusLabelsPath -Encoding UTF8 | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne '' }
    foreach ($line in $plusLines) {
        $cols = $line -split ';'
        if ($cols.Count -lt 6) { continue }
        $index = [int]$cols[0]
        $sci = $cols[2].Trim()
        $com = $cols[3].Trim()
        $class = $cols[4].Trim()
        $raw = "{0}_{1}" -f $sci, $com
        $plusStats['total']++

        if ($class -ne 'Aves') {
            $plusRows.Add(("{0}`t`t{1}" -f $index, $raw))
            $plusStats['no-ave'] = 1 + $(if ($plusStats.ContainsKey('no-ave')) { $plusStats['no-ave'] } else { 0 })
            continue
        }

        $pair = Resolve-ByName $sci
        if ($null -ne $pair) {
            $plusRows.Add(("{0}`t{1}`t{2}" -f $index, $pair[0], $raw))
            $via = $pair[1]
        } else {
            $plusRows.Add(("{0}`t`t{1}" -f $index, $raw))
            $via = 'sin mapear'
        }
        $plusStats[$via] = 1 + $(if ($plusStats.ContainsKey($via)) { $plusStats[$via] } else { 0 })
    }
    # Las filas van ordenadas por indice, que es como las lee la app.
    $sorted = $plusRows | Sort-Object { [int]($_ -split "`t")[0] }
    $plusRows = New-Object System.Collections.Generic.List[string]
    foreach ($r in $sorted) { $plusRows.Add($r) }
} else {
    Write-Host "Sin etiquetas de BirdNET+ ($plusLabelsPath): ejecuta fetch-models.ps1 primero." -ForegroundColor Yellow
}

Set-Content -Path $synonymCachePath -Encoding UTF8 -Value ($synonyms | ConvertTo-Json -Depth 3)

# --- Salida -----------------------------------------------------------------
$speciesFile = Join-Path $outDir 'species.tsv'
$birdnetFile = Join-Path $outDir 'model_labels_birdnet-v2.4.tsv'
$aiyFile     = Join-Path $outDir 'model_labels_aiy-birds-v1.tsv'
$plusFile    = Join-Path $outDir 'model_labels_birdnetplus-v3.0.tsv'

$speciesHeader = "speciesId`tscientificName`tgenus`tfamily`torder`tcommonName`tebirdCode"

Set-Content -Path $speciesFile -Encoding UTF8 -Value (@($speciesHeader) + @($speciesRows.Values))
Set-Content -Path $birdnetFile -Encoding UTF8 -Value (@($labelsHeader) + $birdnetRows)
Set-Content -Path $aiyFile     -Encoding UTF8 -Value (@($labelsHeader) + $aiyRows)
if ($plusRows.Count -gt 0) {
    Set-Content -Path $plusFile -Encoding UTF8 -Value (@($labelsHeader) + $plusRows)
}

Write-Host ''
Write-Host ("  {0,-38} {1,6:N0} especies" -f 'species.tsv', $speciesRows.Count) -ForegroundColor Green
Write-Summary $birdnetFile $birdnetStats
Write-Summary $aiyFile $aiyStats
if ($plusRows.Count -gt 0) { Write-Summary $plusFile $plusStats }

if ($unmapped.Count -gt 0) {
    $auditFile = Join-Path $cacheDir 'unmapped.tsv'
    Set-Content -Path $auditFile -Encoding UTF8 -Value (@("labelIndex`ttaxoCode`trawLabel") + $unmapped)
    Write-Host ''
    Write-Host "Clases de BirdNET v2.4 sin especie canonica (auditar a mano, $auditFile):"
    $unmapped | Select-Object -First 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
}

# Si esto falla, un fichero de etiquetas no corresponde a su modelo y TODAS las
# identificaciones saldrian desplazadas. Mejor reventar el build.
$failed = $false
if ($birdnetRows.Count -ne 6522) { Write-Host "ERROR: BirdNET v2.4: se esperaban 6522 clases y hay $($birdnetRows.Count)." -ForegroundColor Red; $failed = $true }
if ($aiyRows.Count -ne 965) { Write-Host "ERROR: AIY: se esperaban 965 clases y hay $($aiyRows.Count)." -ForegroundColor Red; $failed = $true }
if ($plusRows.Count -gt 0 -and $plusRows.Count -ne 9789) { Write-Host "ERROR: BirdNET+: se esperaban 9789 clases y hay $($plusRows.Count)." -ForegroundColor Red; $failed = $true }
if ($failed) { exit 1 }

Write-Host ''
Write-Host 'Catalogo generado.' -ForegroundColor Green
