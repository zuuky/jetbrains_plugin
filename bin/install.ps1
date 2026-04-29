#!/usr/bin/env pwsh

$projectDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $projectDir

$isCloud = $args -contains "--cloud"

# Create SWEEP_PUBLIC_ENV file for cloud environment if --cloud flag is provided
if ($isCloud)
{
    New-Item -ItemType Directory -Force -Path "src/main/resources" | Out-Null
    "ENVIRONMENT=cloud" | Out-File -FilePath "src/main/resources/SWEEP_PUBLIC_ENV" -Encoding UTF8
}

# Build the plugin
if ($isCloud)
{
    ./gradlew buildPlugin "-Pcloud=true"
}
else
{
    ./gradlew buildPlugin
}

if ($LASTEXITCODE -ne 0)
{
    Write-Error "Gradle build failed"
    exit 1
}

# Find plugin zip file (most recent)
$pluginFile = Get-ChildItem -Path "build/distributions" -Filter "*.zip" | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName

# If in cloud mode, rename the zip file to prepend "cloud" only if not already prefixed
if ($isCloud -and $pluginFile)
{
    $fileName = Split-Path -Leaf $pluginFile
    if ($fileName -notmatch "^cloud-")
    {
        $cloudPluginFile = Join-Path (Split-Path -Parent $pluginFile) "cloud-$fileName"
        Move-Item -Path $pluginFile -Destination $cloudPluginFile -Force
        $pluginFile = $cloudPluginFile
    }
}

# JetBrains plugin directories on Windows
$jetbrainsBase = "$env:APPDATA\JetBrains"
$ideaDirs = @(
    "$jetbrainsBase\IntelliJIdea*",
    "$jetbrainsBase\PyCharm*",
    "$jetbrainsBase\WebStorm*",
    "$jetbrainsBase\CLion*",
    "$jetbrainsBase\RubyMine*",
    "$jetbrainsBase\Rider*"
)

foreach ($pattern in $ideaDirs)
{
    Get-ChildItem -Path $jetbrainsBase -Directory -Filter ($pattern -replace '\*', '*') -ErrorAction SilentlyContinue | ForEach-Object {
        $ideaDir = $_.FullName
        $pluginsDir = Join-Path $ideaDir "plugins"
        $sweepPluginDirs = Get-ChildItem -Path $pluginsDir -Directory -Filter "sweepai*" -ErrorAction SilentlyContinue

        foreach ($dir in $sweepPluginDirs)
        {
            Remove-Item -Path $dir.FullName -Recurse -Force
            Write-Host "Removed old plugin: $( $dir.FullName )"
        }

        if ($pluginFile)
        {
            New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
            Expand-Archive -Path $pluginFile -DestinationPath $pluginsDir -Force
            Write-Host "Installed plugin to: $pluginsDir"
        }
    }
}

# Clean up SWEEP_PUBLIC_ENV file if --cloud flag was used
if ($isCloud)
{
    $envFile = "src/main/resources/SWEEP_PUBLIC_ENV"
    if (Test-Path $envFile)
    {
        git restore $envFile 2> $null
        if (-not $?)
        {
            Remove-Item -Force $envFile
        }
    }
}

Write-Host ""
Write-Host -ForegroundColor Red "Installed plugin successfully. Restart your IDE to see changes"