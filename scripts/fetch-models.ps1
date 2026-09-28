<#
.SYNOPSIS
    Descarga los modelos y etiquetas de BirdNET a los assets del proyecto.

.DESCRIPTION
    Los modelos NO se versionan, por dos razones independientes:

      1. Tamano: son ~39 MB que no pintan nada en un repositorio de codigo.
      2. Licencia: los modelos de BirdNET son CC BY-NC-SA 4.0. El codigo de este
         proyecto no lo es. Mezclarlos en el mismo repositorio enturbia ambas
         cosas.

    LICENCIA - LEE ESTO
    Modelos BirdNET (c) K. Lisa Yang Center for Conservation Bioacoustics,
    Cornell Lab of Ornithology. CC BY-NC-SA 4.0.
      - Uso personal, educativo y de investigacion: permitido.
      - Uso comercial: NO, sin permiso expreso -> ccb-birdnet@cornell.edu
      - La atribucion es requisito de licencia, no cortesia.

    Por eso estos ficheros solo alimentan el flavor `personal`. El flavor `open`
    se construye sin ellos.

.EXAMPLE
    pwsh -File scripts/fetch-models.ps1
#>
[CmdletBinding()]
param(
    # FP16 (24,7 MB) frente a FP32 (49,3 MB). FP16 es la mitad de tamano y la
    # perdida de precision es despreciable para inferencia en movil.
    [ValidateSet('FP16', 'FP32')]
    [string]$Precision = 'FP16',

    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$assetsDir = Join-Path $repoRoot 'core\ml\birdnet\src\main\assets'

$modelsBase = 'https://raw.githubusercontent.com/woheller69/whoBIRD-TFlite/master'
$labelsBase = 'https://raw.githubusercontent.com/woheller69/whoBIRD/master/app/src/main/assets'

$files = @(
    @{
        Name    = "BirdNET_GLOBAL_6K_V2.4_Model_$Precision.tflite"
        Url     = "$modelsBase/BirdNET_GLOBAL_6K_V2.4_Model_$Precision.tflite"
        Purpose = 'Clasificador de cantos. Entrada float32[1,144000] = 3 s @ 48 kHz.'
    },
    @{
        Name    = 'BirdNET_GLOBAL_6K_V2.4_MData_Model_V2_FP16.tflite'
        Url     = "$modelsBase/BirdNET_GLOBAL_6K_V2.4_MData_Model_V2_FP16.tflite"
        Purpose = 'Meta-modelo geo-temporal: el prior del flavor personal.'
    },
    @{
        Name    = 'labels_es.txt'
        Url     = "$labelsBase/labels_es.txt"
        Purpose = '6522 etiquetas en el mismo orden que el tensor de salida.'
    },
    @{
        Name    = 'taxo_code.txt'
        Url     = "$labelsBase/taxo_code.txt"
        Purpose = 'Codigos eBird por clase, para el cross-walk taxonomico.'
    }
)

New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null

Write-Host ''
Write-Host 'Modelos BirdNET (CC BY-NC-SA 4.0) - solo uso no comercial' -ForegroundColor Yellow
Write-Host "Destino: $assetsDir"
Write-Host ''

foreach ($file in $files) {
    $target = Join-Path $assetsDir $file.Name

    if ((Test-Path $target) -and -not $Force) {
        $mb = [math]::Round((Get-Item $target).Length / 1MB, 2)
        Write-Host ("  [ya esta]  {0}  ({1} MB)" -f $file.Name, $mb) -ForegroundColor DarkGray
        continue
    }

    Write-Host ("  descargando {0} ..." -f $file.Name) -NoNewline
    Invoke-WebRequest -Uri $file.Url -OutFile $target -UseBasicParsing -TimeoutSec 300
    $mb = [math]::Round((Get-Item $target).Length / 1MB, 2)
    Write-Host (" {0} MB" -f $mb) -ForegroundColor Green
    Write-Host ("               {0}" -f $file.Purpose) -ForegroundColor DarkGray
}

Write-Host ''
Write-Host 'SHA256 (anotalos: sirven para detectar que un modelo ha cambiado bajo tus pies)'
foreach ($file in $files) {
    $target = Join-Path $assetsDir $file.Name
    if (Test-Path $target) {
        Write-Host ('  {0}  {1}' -f (Get-FileHash $target -Algorithm SHA256).Hash.ToLower(), $file.Name)
    }
}

# El fichero de etiquetas debe tener exactamente 6522 lineas. Si no las tiene,
# no corresponde al modelo y TODAS las identificaciones saldrian desplazadas:
# un fallo silencioso que se manifestaria como "la app acierta fatal".
$labelsPath = Join-Path $assetsDir 'labels_es.txt'
if (Test-Path $labelsPath) {
    $count = @(Get-Content $labelsPath | Where-Object { $_.Trim() -ne '' }).Count
    Write-Host ''
    if ($count -eq 6522) {
        Write-Host "  OK: labels_es.txt tiene $count etiquetas." -ForegroundColor Green
    } else {
        Write-Host "  ERROR: labels_es.txt tiene $count etiquetas, se esperaban 6522." -ForegroundColor Red
        exit 1
    }
}

# --- Clasificador de imagen (ambos flavors) ----------------------------------
# El labelmap se publica aparte y es accesible sin cuenta; el .tflite no.
$visionDir = Join-Path $repoRoot (Join-Path "core" (Join-Path "ml" (Join-Path "vision" (Join-Path "src" (Join-Path "main" "assets")))))
New-Item -ItemType Directory -Force -Path $visionDir | Out-Null
$labelmapPath = Join-Path $visionDir 'aiy_birds_labelmap.csv'

if ((-not (Test-Path $labelmapPath)) -or $Force) {
    Write-Host ''
    Write-Host '  descargando aiy_birds_labelmap.csv ...' -NoNewline
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 120 `
        -Uri 'https://www.gstatic.com/aihub/tfhub/labelmaps/aiy_birds_V1_labelmap.csv' `
        -OutFile $labelmapPath
    Write-Host (' {0} KB' -f [math]::Round((Get-Item $labelmapPath).Length / 1KB)) -ForegroundColor Green
}

$visionModel = Get-ChildItem $visionDir -Filter '*.tflite' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $visionModel) {
    Write-Host ''
    Write-Host 'FALTA EL MODELO DE IMAGEN (hay que descargarlo a mano)' -ForegroundColor Yellow
    Write-Host '  kaggle.com/models/google/aiy/tfLite/vision-classifier-birds-v1'
    Write-Host '  Variacion 3, y SOLO la 3: su changelog dice que corrige un'
    Write-Host '  "out-of-order logit to labelmap mapping". Las versiones 1 y 2'
    Write-Host '  devuelven la especie equivocada sin dar ningun sintoma.' -ForegroundColor Yellow
    Write-Host '  Apache 2.0, 3,4 MB. Requiere cuenta de Kaggle.'
    Write-Host ('  Dejalo en: {0}' -f $visionDir)
} else {
    Write-Host ('  [ya esta]  {0}  ({1} MB)' -f $visionModel.Name, [math]::Round($visionModel.Length / 1MB, 2)) -ForegroundColor DarkGray
}

Write-Host ''
Write-Host "Listo. Construye con: gradlew.bat :app:assembleDebug"
Write-Host ''

# --- BirdNET+ V3, pesos Apache 2.0 (flavor open) ----------------------------
#
# ESTOS SI SE PUEDEN DISTRIBUIR. Los de BirdNET v2.4 son CC BY-NC-SA; estos los
# publica el propio equipo de BirdNET bajo Apache 2.0 en birdnet-live-app, y su
# fichero MODEL_LICENSE lo dice literalmente. Son los que alimentan el flavor
# `open`, el publicable.
#
# Viven en Git LFS, asi que raw.githubusercontent.com sirve un PUNTERO y no el
# contenido. Hay que pasar por la API de lotes de LFS. La ventaja es que el
# puntero trae el sha256, con lo que la descarga se verifica gratis.

$plusDir = Join-Path $repoRoot (Join-Path "core" (Join-Path "ml" (Join-Path "birdnetplus" (Join-Path "src" (Join-Path "main" "assets")))))
New-Item -ItemType Directory -Force -Path $plusDir | Out-Null

$lfsRepo = 'birdnet-team/birdnet-live-app'
$lfsBase = "https://raw.githubusercontent.com/$lfsRepo/main/assets/models"

function Get-LfsAsset([string]$targetName, [string]$encodedName) {
    $target = Join-Path $plusDir $targetName
    if ((Test-Path $target) -and -not $Force) {
        Write-Host ("  [ya esta]  {0}  ({1} MB)" -f $targetName, [math]::Round((Get-Item $target).Length / 1MB, 2)) -ForegroundColor DarkGray
        return
    }

    $pointer = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 120 -Uri "$lfsBase/$encodedName").Content
    if ($pointer -is [byte[]]) { $pointer = [System.Text.Encoding]::UTF8.GetString($pointer) }

    if ($pointer -notmatch 'git-lfs') {
        # No esta en LFS: se sirve directo.
        Invoke-WebRequest -UseBasicParsing -TimeoutSec 300 -Uri "$lfsBase/$encodedName" -OutFile $target
        Write-Host ("  descargado {0}  ({1} MB)" -f $targetName, [math]::Round((Get-Item $target).Length / 1MB, 2)) -ForegroundColor Green
        return
    }

    $oid = ([regex]::Match($pointer, 'sha256:([a-f0-9]+)')).Groups[1].Value
    $size = [long]([regex]::Match($pointer, 'size (\d+)')).Groups[1].Value

    $body = @{
        operation = 'download'
        transfers = @('basic')
        objects   = @(@{ oid = $oid; size = $size })
    } | ConvertTo-Json -Depth 4

    $batch = Invoke-RestMethod -Method Post -TimeoutSec 120 `
        -Uri "https://github.com/$lfsRepo.git/info/lfs/objects/batch" `
        -Headers @{ Accept = 'application/vnd.git-lfs+json' } `
        -ContentType 'application/vnd.git-lfs+json' -Body $body

    $href = $batch.objects[0].actions.download.href
    Write-Host ("  descargando {0} ..." -f $targetName) -NoNewline
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 900 -Uri $href -OutFile $target

    # El puntero trae el hash, asi que la verificacion sale gratis.
    $got = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
    if ($got -ne $oid) {
        Write-Host ' HASH NO COINCIDE' -ForegroundColor Red
        Remove-Item $target -Force
        exit 1
    }
    Write-Host (" {0} MB  sha256 verificado" -f [math]::Round((Get-Item $target).Length / 1MB, 2)) -ForegroundColor Green
}

Write-Host ''
Write-Host 'BirdNET+ V3 (Apache 2.0) - estos SI se pueden distribuir' -ForegroundColor Green

Get-LfsAsset 'birdnetplus_audio.onnx'     'BirdNET%2B_V3.0-preview3.1_Global_10K-pruned_FP16.onnx'
Get-LfsAsset 'birdnetplus_geo.onnx'       'BirdNET%2B_Geomodel_V3.0.4_Global_10K-pruned_FP16.onnx'
Get-LfsAsset 'birdnetplus_labels.csv'     'BirdNET%2B_V3.0-preview3.1_Global_10K-pruned_Labels.csv'
Get-LfsAsset 'birdnetplus_geo_labels.txt' 'BirdNET%2B_Geomodel_V3.0.4_Global_10K-pruned_Labels.txt'
