# Shared bounded SDK calls. Fixture data stays on stdin, never command arguments.
function Invoke-TestAdb([string[]]$Arguments,[int]$TimeoutMs=10000,[string]$InputText){
    $start=[Diagnostics.ProcessStartInfo]::new($adb);$start.UseShellExecute=$false;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true;$start.CreateNoWindow=$true
    $start.RedirectStandardInput=$PSBoundParameters.ContainsKey('InputText')
    foreach($argument in $Arguments){$start.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::Start($start);$output=$process.StandardOutput.ReadToEndAsync();$errors=$process.StandardError.ReadToEndAsync()
    if($start.RedirectStandardInput){$process.StandardInput.Write($InputText);$process.StandardInput.Close()}
    if(!$process.WaitForExit($TimeoutMs)){$process.Kill();if(!$process.WaitForExit(5000)){throw 'Test ADB helper did not stop'};return @{code=-1;output='';timed_out=$true}}
    return @{code=$process.ExitCode;output=$output.GetAwaiter().GetResult();timed_out=$false}
}
