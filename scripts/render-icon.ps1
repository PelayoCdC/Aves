# Previsualiza el icono adaptativo sin Android Studio: pinta el fondo, los
# trazados de scripts/icon-paths.txt con la misma transformacion que el vector
# (60 % centrado en 108) y recorta con una mascara redonda, que es la mas
# exigente. Deja icon-preview.png junto a este script.
Add-Type -AssemblyName PresentationCore, PresentationFramework, WindowsBase

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$size = 432
$unit = $size / 108.0

$visual = New-Object System.Windows.Media.DrawingVisual
$dc = $visual.RenderOpen()

# Mascara redonda (lo que ensena Pixel y la mayoria de lanzadores).
$clip = New-Object System.Windows.Media.EllipseGeometry (New-Object System.Windows.Point ($size / 2), ($size / 2)), ($size / 2), ($size / 2)
$dc.PushClip($clip)

$gradient = New-Object System.Windows.Media.LinearGradientBrush
$gradient.StartPoint = New-Object System.Windows.Point 0, 0
$gradient.EndPoint = New-Object System.Windows.Point 1, 1
$gradient.GradientStops.Add((New-Object System.Windows.Media.GradientStop ([System.Windows.Media.ColorConverter]::ConvertFromString('#FF6A9A4F')), 0))
$gradient.GradientStops.Add((New-Object System.Windows.Media.GradientStop ([System.Windows.Media.ColorConverter]::ConvertFromString('#FF2F4A27')), 1))
$dc.DrawRectangle($gradient, $null, (New-Object System.Windows.Rect 0, 0, $size, $size))

# La misma transformacion que el <group> del vector.
$dc.PushTransform((New-Object System.Windows.Media.ScaleTransform $unit, $unit))
$dc.PushTransform((New-Object System.Windows.Media.TranslateTransform 24, 24))
$dc.PushTransform((New-Object System.Windows.Media.ScaleTransform 0.6, 0.6))

Get-Content (Join-Path $root 'icon-paths.txt') | ForEach-Object {
    if ($_.Trim().Length -eq 0) { return }
    $parts = $_ -split '\|'
    $kind = $parts[0]
    $color = [System.Windows.Media.ColorConverter]::ConvertFromString($parts[1])
    if ($kind -eq 'fill') {
        $geometry = [System.Windows.Media.Geometry]::Parse($parts[2])
        $dc.DrawGeometry((New-Object System.Windows.Media.SolidColorBrush $color), $null, $geometry)
    } else {
        $geometry = [System.Windows.Media.Geometry]::Parse($parts[3])
        $pen = New-Object System.Windows.Media.Pen (New-Object System.Windows.Media.SolidColorBrush $color), ([double]$parts[2])
        $pen.StartLineCap = 'Round'
        $pen.EndLineCap = 'Round'
        $dc.DrawGeometry($null, $pen, $geometry)
    }
}

$dc.Pop(); $dc.Pop(); $dc.Pop(); $dc.Pop()
$dc.Close()

$bitmap = New-Object System.Windows.Media.Imaging.RenderTargetBitmap $size, $size, 96, 96, ([System.Windows.Media.PixelFormats]::Pbgra32)
$bitmap.Render($visual)
$encoder = New-Object System.Windows.Media.Imaging.PngBitmapEncoder
$encoder.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
$out = Join-Path $root 'icon-preview.png'
$stream = [System.IO.File]::Create($out)
$encoder.Save($stream)
$stream.Close()
Write-Output $out
