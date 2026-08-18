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
