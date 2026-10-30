param([string]$Tsv = "", [string]$Res = (Join-Path $PSScriptRoot "..\..\app\src\main\res"))
# Tabela: klucz <TAB> en <TAB> pl <TAB> de <TAB> cs <TAB> sk ; wiersze "#" to komentarze.
# Liczba mnoga: klucz "plural:nazwa", wartości "one:..|few:..|many:..|other:..".
# Tablica: klucz "array:nazwa", elementy rozdzielone " || ".
$enc = New-Object System.Text.UTF8Encoding($false)
# Bez -Tsv: wszystkie tabele z tego katalogu
if ($Tsv -eq "") { Get-ChildItem $PSScriptRoot -Filter *.tsv | % { & $PSCommandPath -Tsv $_.FullName -Res $Res }; return }
$langs = @("", "-pl", "-de", "-cs", "-sk")
$name = [IO.Path]::GetFileNameWithoutExtension($Tsv)
function Esc([string]$s) {
    $s = $s.Replace('&', '&amp;').Replace('<', '&lt;').Replace('>', '&gt;')
    $s = $s -replace "(?<!\\)'", "\'"
    $s = $s -replace '(?<!\\)"', '\"'
    if ($s.StartsWith('@') -or $s.StartsWith('?')) { $s = '\' + $s }
    return $s
}
$rows = @()
$n = 0
foreach ($line in [IO.File]::ReadAllLines($Tsv, $enc)) {
    $n++
    if ($line.Trim() -eq "" -or $line.StartsWith("#")) { continue }
    $cols = $line.Split("`t")
    if ($cols.Count -ne 6) { throw "$($Tsv):$n – oczekiwano 6 kolumn, jest $($cols.Count): $line" }
    $rows += ,$cols
}
$keys = $rows | % { $_[0] }
$dup = $keys | Group-Object | ? Count -gt 1
if ($dup) { throw "Powtórzone klucze: $($dup.Name -join ', ')" }
for ($i = 0; $i -lt 5; $i++) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('<?xml version="1.0" encoding="utf-8"?>')
    [void]$sb.AppendLine("<!-- Wygenerowane z tabeli tłumaczeń $name.tsv -->")
    [void]$sb.AppendLine('<resources>')
    foreach ($r in $rows) {
        $key = $r[0]; $val = $r[$i + 1]
        if ($key.StartsWith("plural:")) {
            [void]$sb.AppendLine("    <plurals name=""$($key.Substring(7))"">")
            foreach ($part in $val.Split('|')) {
                $q = $part.Substring(0, $part.IndexOf(':')); $t = $part.Substring($part.IndexOf(':') + 1)
                [void]$sb.AppendLine("        <item quantity=""$q"">$(Esc $t)</item>")
            }
            [void]$sb.AppendLine("    </plurals>")
        } elseif ($key.StartsWith("array:")) {
            [void]$sb.AppendLine("    <string-array name=""$($key.Substring(6))"">")
            foreach ($item in ($val -split ' \|\| ')) { [void]$sb.AppendLine("        <item>$(Esc $item)</item>") }
            [void]$sb.AppendLine("    </string-array>")
        } else {
            $attr = if ($val -match '%\d\$') { '' } elseif ($val -match '%') { ' formatted="false"' } else { '' }
            [void]$sb.AppendLine("    <string name=""$key""$attr>$(Esc $val)</string>")
        }
    }
    [void]$sb.AppendLine('</resources>')
    $dir = Join-Path $Res ("values" + $langs[$i])
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory $dir | Out-Null }
    [IO.File]::WriteAllText((Join-Path $dir "$name.xml"), $sb.ToString(), $enc)
}
"$name : $($rows.Count) wpisów x 5 języków"
