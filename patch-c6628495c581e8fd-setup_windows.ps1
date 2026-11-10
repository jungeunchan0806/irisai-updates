$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
function Find-Python {
    $ErrorActionPreference = 'SilentlyContinue'
    $candidates = @(
        "$env:LOCALAPPDATA\Programs\Python\Python311\python.exe",
        "$env:ProgramFiles\Python311\python.exe"
    )
    if (Get-Command py -ErrorAction SilentlyContinue) {
        $found = & py -3.11 -c "import sys; print(sys.executable)" 2>$null
        if ($LASTEXITCODE -eq 0) { $candidates = @($found) + $candidates }
    }
    if (Get-Command python -ErrorAction SilentlyContinue) {
        $candidates += (Get-Command python).Source
    }
    foreach ($candidate in $candidates) {
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        & $candidate -c "import sys,struct; sys.exit(0 if sys.version_info[:2] == (3,11) and struct.calcsize('P') == 8 else 1)" 2>$null
        if ($LASTEXITCODE -eq 0) { return $candidate }
    }
    return $null
}
try {
    $pythonExe = Find-Python
    if (-not $pythonExe) {
        if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
            throw 'Install Python 3.11 x64 from python.org, then run Setup-Windows.bat again.'
        }
        Write-Host 'Installing Python 3.11 x64 for the current Windows user...'
        & winget install --exact --id Python.Python.3.11 --scope user --architecture x64 --source winget --accept-source-agreements --accept-package-agreements
        if ($LASTEXITCODE -ne 0) { throw 'Python installation failed. Install Python 3.11 x64 manually and retry.' }
        $pythonExe = Find-Python
        if (-not $pythonExe) { throw 'Python was installed but could not be located. Reopen Setup-Windows.bat.' }
    }
    & $pythonExe -m pyla_addons.bootstrap
    if ($LASTEXITCODE -ne 0) { throw 'Dependency setup failed. Check the error above and retry.' }
    Write-Host 'Setup complete. Run Start-Windows.bat.'
    exit 0
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
