param(
  [string]$InputJsonl = "android-app/src/main/assets/elnet_puncheryshte.ttmorph.jsonl",
  [string]$OutputJsonl = "android-app/src/main/assets/elnet_puncheryshte.vienna.ttmorph.jsonl",
  [string]$CachePath = ".codex/mari/vienna-sentence-cache.json",
  [int]$MaxSentences = 0,
  [int]$Concurrency = 3,
  [int]$DelayMs = 350
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$mariWord = '^\p{IsCyrillic}+(?:[-'']\p{IsCyrillic}+)*$'
$sentenceEnd = '^[.!?…]+$'
$uri = [Uri]"https://mari-language.univie.ac.at/analyzer.php?int=0"

function Read-Jsonl($path) {
  [System.IO.File]::ReadLines((Resolve-Path $path)) |
    Where-Object { $_.Trim().Length -gt 0 } |
    ForEach-Object { $_ | ConvertFrom-Json }
}

function ConvertTo-FlatJson($value) {
  $value | ConvertTo-Json -Depth 30 -Compress
}

function Load-Cache($path) {
  $cache = @{}
  if (Test-Path $path) {
    $raw = Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($property in $raw.PSObject.Properties) {
      $cache[$property.Name] = @($property.Value)
    }
  }
  $cache
}

function Save-Cache($path, $cache) {
  $dir = Split-Path $path -Parent
  if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  $cache | ConvertTo-Json -Depth 30 | Set-Content $path -Encoding UTF8
}

function New-Sentence($records, $start, $end) {
  if ($end -le $start) { return $null }
  $parts = New-Object System.Collections.Generic.List[string]
  $words = New-Object System.Collections.Generic.List[int]
  for ($i = $start; $i -lt $end; $i++) {
    $record = $records[$i]
    $parts.Add("$($record.prefix)$($record.surface)")
    $surface = [string]$record.surface
    if ($surface.ToLowerInvariant() -match $mariWord) {
      $words.Add($i)
    }
  }
  $text = (($parts -join "") -replace '\s+', ' ').Trim()
  if (!$text) { return $null }
  [pscustomobject]@{ start = $start; end = $end; text = $text; words = @($words) }
}

function Split-Sentences($records) {
  $sentences = New-Object System.Collections.Generic.List[object]
  $start = 0
  for ($i = 0; $i -lt $records.Count; $i++) {
    $surface = [string]$records[$i].surface
    if ($surface -match $sentenceEnd) {
      $sentence = New-Sentence $records $start ($i + 1)
      if ($sentence) { $sentences.Add($sentence) }
      $start = $i + 1
    } elseif ((([string]$records[$i].prefix).Contains("`n`n")) -and $i -gt $start) {
      $sentence = New-Sentence $records $start $i
      if ($sentence) { $sentences.Add($sentence) }
      $start = $i
    }
  }
  if ($start -lt $records.Count) {
    $sentence = New-Sentence $records $start $records.Count
    if ($sentence) { $sentences.Add($sentence) }
  }
  return $sentences.ToArray()
}

function Strip-Tags($value) {
  ([string]$value) `
    -replace '<[^>]*>', '' `
    -replace '&nbsp;', ' ' `
    -replace '&lt;', '<' `
    -replace '&gt;', '>' `
    -replace '&amp;', '&' `
    -replace '\s+', ' '
}

function Read-Row($fragment, $className) {
  $match = [regex]::Match($fragment, "<tr class=""$className"">([\s\S]*?)</tr>")
  if ($match.Success) { return $match.Groups[1].Value }
  ""
}

function Read-Cells($fragment, $className) {
  $row = Read-Row $fragment $className
  @([regex]::Matches($row, '<td[^>]*>([\s\S]*?)</td>') | ForEach-Object {
    (Strip-Tags $_.Groups[1].Value).Trim()
  })
}

function Read-FirstWord($value) {
  $match = [regex]::Match([string]$value, "\p{IsCyrillic}+(?:[-']\p{IsCyrillic}+)*")
  if ($match.Success) { return $match.Value.ToLowerInvariant() }
  ""
}

function Parse-Vienna($html) {
  $items = New-Object System.Collections.Generic.List[object]
  foreach ($cell in (([string]$html) -split '<!--NEXTWORD-->')) {
    $itemWord = ""
    $variants = New-Object System.Collections.Generic.List[object]
    foreach ($table in [regex]::Matches($cell, '<table>([\s\S]*?)</table>')) {
      $fragment = $table.Groups[1].Value
      $rawWord = (Strip-Tags (Read-Row $fragment "headword")).Trim().ToLowerInvariant()
      $word = Read-FirstWord $rawWord
      if (!$word) { continue }
      $segments = @(Read-Cells $fragment "div" | Where-Object { $_ })
      if (!$itemWord) { $itemWord = $word }
      $variants.Add([pscustomobject]@{
        lemma = if ($segments.Count -gt 0) { $segments[0] } else { $word }
        segments = $segments
        gloss = @(Read-Cells $fragment "gloss")
        pos = @(Read-Cells $fragment "pos")
      })
    }
    if ($itemWord) {
      $items.Add([pscustomobject]@{ word = $itemWord; variants = @($variants.ToArray()) })
    }
  }
  return $items.ToArray()
}

function Invoke-Vienna($client, $sentence) {
  $pairs = [System.Collections.Generic.List[System.Collections.Generic.KeyValuePair[string,string]]]::new()
  $pairs.Add([System.Collections.Generic.KeyValuePair[string,string]]::new("inField", $sentence.text))
  $pairs.Add([System.Collections.Generic.KeyValuePair[string,string]]::new("dnt", "dnt"))
  $content = [System.Net.Http.FormUrlEncodedContent]::new($pairs)
  $response = $client.PostAsync($uri, $content).GetAwaiter().GetResult()
  $response.EnsureSuccessStatusCode() | Out-Null
  Parse-Vienna ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult())
}

function Format-Analysis($variant) {
  $lemma = if ($variant.lemma) { $variant.lemma } elseif ($variant.segments.Count -gt 0) { $variant.segments[0] } else { "Unknown" }
  $pos = @($variant.pos | Where-Object { $_ }) -join '+'
  $gloss = @($variant.gloss | Where-Object { $_ }) -join '+'
  $segments = @($variant.segments | Where-Object { $_ }) -join '-'
  $tail = @($pos, $gloss | Where-Object { $_ }) -join '|'
  if ($tail) { "$lemma+$tail ($segments)" } else { "$lemma ($segments)" }
}

function Convert-Records($records, $sentences, $cache) {
  $result = @($records | ForEach-Object {
    $_ | Add-Member -NotePropertyName analyzer -NotePropertyValue "vienna" -Force -PassThru |
      Add-Member -NotePropertyName analyses -NotePropertyValue @() -Force -PassThru
  })
  foreach ($sentence in $sentences) {
    $sequence = @($cache[$sentence.text])
    $sequenceIndex = 0
    foreach ($recordIndex in $sentence.words) {
      $record = $result[$recordIndex]
      $word = ([string]$record.surface).ToLowerInvariant()
      while ($sequenceIndex -lt $sequence.Count -and $sequence[$sequenceIndex].word -ne $word) {
        $sequenceIndex++
      }
      $item = if ($sequenceIndex -lt $sequence.Count) { $sequence[$sequenceIndex] } else { $null }
      $analyses = @()
      if ($item -and $item.word -eq $word) {
        $analyses = @($item.variants | ForEach-Object {
          [pscustomobject]@{
            analysis = Format-Analysis $_
            lemma = $_.lemma
            segments = $_.segments
            gloss = $_.gloss
            pos = $_.pos
          }
        })
        $sequenceIndex++
      }
      $record.analysis = if ($analyses.Count -gt 0) { $analyses[0].analysis } else { "Unknown" }
      $record.analyses = $analyses
    }
  }
  foreach ($record in $result) {
    if (!(([string]$record.surface).ToLowerInvariant() -match $mariWord)) {
      if (!$record.analysis) { $record.analysis = "Sign" }
    } elseif (!$record.analysis) {
      $record.analysis = "Unknown"
    }
  }
  $result
}

$records = @(Read-Jsonl $InputJsonl)
$cache = Load-Cache $CachePath
$sentences = @(Split-Sentences $records)
$remaining = @($sentences | Where-Object { $_.words.Count -gt 0 -and !$cache.ContainsKey($_.text) })
$todo = if ($MaxSentences -gt 0) { @($remaining | Select-Object -First $MaxSentences) } else { $remaining }

Write-Host "Loaded $($records.Count) records from $InputJsonl"
Write-Host "Built $($sentences.Count) sentence-like chunks"
Write-Host "Vienna cache has $($cache.Count) sentences; $($remaining.Count) sentences need analysis"
if ($MaxSentences -gt 0 -and $remaining.Count -gt $todo.Count) {
  Write-Host "This run is capped at $($todo.Count) sentences"
}

$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(90)
$done = 0
foreach ($sentence in $todo) {
  try {
    $cache[$sentence.text] = @(Invoke-Vienna $client $sentence)
  } catch {
    Write-Warning "Vienna failed for sentence '$($sentence.text.Substring(0, [Math]::Min(80, $sentence.text.Length)))': $($_.Exception.Message)"
    $cache[$sentence.text] = @()
  }
  $done++
  if ($done % 10 -eq 0 -or $done -eq $todo.Count) {
    Save-Cache $CachePath $cache
  }
  if ($done % 50 -eq 0 -or $done -eq $todo.Count) {
    Write-Host "Analysed $done / $($todo.Count)"
  }
  if ($DelayMs -gt 0) { Start-Sleep -Milliseconds $DelayMs }
}
Save-Cache $CachePath $cache

$viennaRecords = @(Convert-Records $records $sentences $cache)
$outputDir = Split-Path $OutputJsonl -Parent
if ($outputDir) { New-Item -ItemType Directory -Force -Path $outputDir | Out-Null }
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) $OutputJsonl), @($viennaRecords | ForEach-Object { ConvertTo-FlatJson $_ }), [System.Text.UTF8Encoding]::new($false))

$wordRecords = @($viennaRecords | Where-Object { ([string]$_.surface).ToLowerInvariant() -match $mariWord })
$analysed = @($wordRecords | Where-Object { $_.analyses.Count -gt 0 })
$ambiguous = @($wordRecords | Where-Object { $_.analyses.Count -gt 1 })
Write-Host "Wrote $($viennaRecords.Count) records to $OutputJsonl"
Write-Host "Mari-like word tokens: $($wordRecords.Count)"
Write-Host "Analysed by Vienna: $($analysed.Count)"
Write-Host "Ambiguous by Vienna: $($ambiguous.Count)"
Write-Host "Unknown by Vienna: $($wordRecords.Count - $analysed.Count)"
