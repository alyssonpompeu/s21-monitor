# Requer execução elevada. Configura um pagefile fixo de 8 GiB no volume do sistema.
# O Windows pode exigir reinicialização antes de aplicar a nova configuração.
$ErrorActionPreference = 'Stop'

$systemDrive = $env:SystemDrive
$pagePath = "$systemDrive\\pagefile.sys"

$computer = Get-CimInstance -ClassName Win32_ComputerSystem
if ($computer.AutomaticManagedPagefile) {
    Set-CimInstance -InputObject $computer -Property @{ AutomaticManagedPagefile = $false } | Out-Null
}

$existing = Get-CimInstance -ClassName Win32_PageFileSetting -ErrorAction SilentlyContinue | Where-Object { $_.Name -ieq $pagePath }
if ($existing) {
    Set-CimInstance -InputObject $existing -Property @{ InitialSize = 8192; MaximumSize = 8192 } | Out-Null
} else {
    New-CimInstance -ClassName Win32_PageFileSetting -Property @{ Name = $pagePath; InitialSize = 8192; MaximumSize = 8192 } | Out-Null
}

Write-Host "Arquivo de paginação configurado para 8192 MiB em $pagePath. Reinicie o Windows antes de usar a IA para garantir a aplicação." -ForegroundColor Green
