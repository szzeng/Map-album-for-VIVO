$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$repo = Split-Path -Parent $project
$out = Join-Path $env:TEMP 'x300-map-jvm-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$stubs = Get-ChildItem (Join-Path $PSScriptRoot 'jvm') -Recurse -Filter '*.java' | ForEach-Object FullName
$source = Join-Path $repo 'map002-provider-probe/src/org/example/map002probe'
& javac -encoding UTF-8 -d $out $stubs (Join-Path $source 'MapDataRepository.java') (Join-Path $source 'PrimitiveLocationSet.java') (Join-Path $source 'ViewportFilter.java') (Join-Path $project 'app/src/main/java/io/github/szzeng/mapalbum/CoordinateAdapter.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $out org.example.map002probe.RepositoryLifecycleTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $out io.github.szzeng.mapalbum.CoordinateAdapterTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac -encoding UTF-8 -d $out (Join-Path $project 'app/src/main/java/io/github/szzeng/mapalbum/ScreenClusters.java') (Join-Path $PSScriptRoot 'io/github/szzeng/mapalbum/ScreenClustersTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $out io.github.szzeng.mapalbum.ScreenClustersTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac -encoding UTF-8 -d $out (Join-Path $project 'app/src/main/java/io/github/szzeng/mapalbum/ViewportBounds.java') (Join-Path $project 'app/src/main/java/io/github/szzeng/mapalbum/GeographicViewport.java') (Join-Path $PSScriptRoot 'io/github/szzeng/mapalbum/ViewportBoundsTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $out io.github.szzeng.mapalbum.ViewportBoundsTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac -encoding UTF-8 -d $out (Join-Path $project 'app/src/main/java/io/github/szzeng/mapalbum/MarkerCount.java') (Join-Path $PSScriptRoot 'io/github/szzeng/mapalbum/MarkerCountTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $out io.github.szzeng.mapalbum.MarkerCountTest
exit $LASTEXITCODE
