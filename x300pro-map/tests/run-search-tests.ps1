$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$out = Join-Path $env:TEMP 'x300-map-search-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& javac -encoding UTF-8 -d $out (Join-Path $project 'app/src/main/java/io/github/szzeng/mapalbum/PlaceSearchRules.java') (Join-Path $PSScriptRoot 'io/github/szzeng/mapalbum/PlaceSearchRulesTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $out io.github.szzeng.mapalbum.PlaceSearchRulesTest
exit $LASTEXITCODE
