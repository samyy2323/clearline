# Prints the machine's overall CPU utilisation (%), averaged over 3 one-second samples. Used to flag benchmark runs that were not quiet.
$s = (Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 3).CounterSamples | ForEach-Object { $_.CookedValue }
[math]::Round(($s | Measure-Object -Average).Average, 1)
