[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

# Keep Gradle outputs and project state off the NAS. Source files stay in this checkout.
$projectRoot = $PSScriptRoot
$wrapperJar = Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.jar'
$pathHashAlgorithm = [System.Security.Cryptography.SHA256]::Create()
try {
    $pathHashBytes = $pathHashAlgorithm.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($projectRoot))
    $checkoutId = ([System.BitConverter]::ToString($pathHashBytes)).Replace('-', '').Substring(0, 16)
} finally {
    $pathHashAlgorithm.Dispose()
}
$localRoot = Join-Path $env:TEMP ('WorldsGUI-build\' + $checkoutId)
$localBuild = Join-Path $localRoot 'build'
$projectCache = Join-Path $localRoot 'project-cache'
$initScript = Join-Path $localRoot 'local-build.init.gradle'
New-Item -ItemType Directory -Force -Path $localRoot | Out-Null

$groovyBuildPath = $localBuild.Replace('\', '/').Replace("'", "\'")
$initContent = "allprojects { layout.buildDirectory.set(new File('$groovyBuildPath')) }"
[System.IO.File]::WriteAllText($initScript, $initContent, (New-Object System.Text.UTF8Encoding($false)))

if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $javaExecutable = (Get-Command java.exe -ErrorAction Stop).Source
}

Write-Host "Lokale Gradle-Ausgabe: $localBuild"
Push-Location -LiteralPath $projectRoot
try {
    & $javaExecutable -jar $wrapperJar build `
        --project-cache-dir $projectCache `
        --init-script $initScript `
        --no-configuration-cache --no-build-cache --no-watch-fs --console plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle-Build fehlgeschlagen (Exitcode $LASTEXITCODE)."
    }

    # Copy only artifacts produced by this build; no world or source files are touched.
    $localLibraries = Join-Path $localBuild 'libs'
    $builtJars = @(Get-ChildItem -LiteralPath $localLibraries -Filter '*.jar' -File)
    if ($builtJars.Count -eq 0) {
        throw "Build erfolgreich, aber keine JAR unter $localLibraries gefunden."
    }
    $destination = Join-Path $projectRoot 'build\libs'
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    foreach ($jar in $builtJars) {
        $targetJar = Join-Path $destination $jar.Name
        Copy-Item -LiteralPath $jar.FullName -Destination $targetJar -Force
        Write-Host "JAR: $targetJar"
    }
} finally {
    Pop-Location
}
