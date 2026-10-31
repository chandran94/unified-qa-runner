$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:8080/")
$listener.Start()
Write-Host "Local health fixture listening at http://127.0.0.1:8080/health (Ctrl+C to stop)."

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $response = $context.Response
        if ($context.Request.Url.AbsolutePath -eq "/health") {
            $response.StatusCode = 200
            $response.ContentType = "application/json; charset=utf-8"
            $body = [System.Text.Encoding]::UTF8.GetBytes('{"status":"ok"}')
        } else {
            $response.StatusCode = 404
            $response.ContentType = "text/plain; charset=utf-8"
            $body = [System.Text.Encoding]::UTF8.GetBytes("Not found")
        }
        $response.ContentLength64 = $body.Length
        $response.OutputStream.Write($body, 0, $body.Length)
        $response.OutputStream.Close()
    }
} finally {
    $listener.Stop()
    $listener.Close()
}
