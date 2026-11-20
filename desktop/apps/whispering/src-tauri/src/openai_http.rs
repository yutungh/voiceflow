//! VoiceFlow (speed, PLAN-v2 addendum): one pooled HTTPS client for the
//! OpenAI API, warmed at the hotkey.
//!
//! `tauri-plugin-http` builds a new client for every fetch, so each
//! dictation paid a fresh DNS + TCP + TLS handshake twice (transcription,
//! then tidy-up), and it carries request bodies as JSON number arrays. The
//! webview's own fetch would pool connections, but OpenAI's 401 for a bad key
//! has no CORS header, so the app would lose its "update your key" message.
//! This module keeps one `reqwest` client for the whole run: `openai_warm`
//! opens (or keeps) its connection while the person is still talking, and
//! `openai_fetch` sends the SDK's request over it, with raw bodies both ways.
//!
//! Only `https://api.openai.com/` is reachable through here; anything else
//! is refused.

use std::sync::OnceLock;
use std::time::Duration;

const OPENAI_ORIGIN: &str = "https://api.openai.com";
/// A cheap GET that needs no key (it answers 401) and opens the connection.
const WARM_URL: &str = "https://api.openai.com/v1/models";

fn client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            // Long enough to span a dictation and its tidy-up.
            .pool_idle_timeout(Duration::from_secs(300))
            .tcp_keepalive(Duration::from_secs(30))
            .connect_timeout(Duration::from_secs(15))
            // The API never redirects; following one could leave the allow-list.
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .expect("the HTTPS client (rustls) could not be built")
    })
}

/// True only for `https://api.openai.com/...` (no other host, port or scheme).
pub fn is_openai_url(raw: &str) -> bool {
    match reqwest::Url::parse(raw) {
        Ok(url) => {
            url.scheme() == "https"
                && url.host_str() == Some("api.openai.com")
                && url.port().is_none()
                && url.username().is_empty()
                && url.password().is_none()
                && raw.starts_with(OPENAI_ORIGIN)
        }
        Err(_) => false,
    }
}

/// Opens or refreshes the pooled connection. Sends no key and no audio.
#[tauri::command]
pub async fn openai_warm() -> bool {
    match client().get(WARM_URL).send().await {
        // Read the (small) body so the connection goes back to the pool.
        Ok(response) => response.bytes().await.is_ok(),
        Err(_) => false,
    }
}

/// Reply layout: u16 status, u32 header-JSON length, header JSON
/// (`[[name, value], ...]`), then the body. Little-endian.
pub fn frame_response(status: u16, headers: &[(String, String)], body: &[u8]) -> Vec<u8> {
    let header_json = serde_json::to_vec(headers).unwrap_or_else(|_| b"[]".to_vec());
    let mut out = Vec::with_capacity(6 + header_json.len() + body.len());
    out.extend_from_slice(&status.to_le_bytes());
    out.extend_from_slice(&(header_json.len() as u32).to_le_bytes());
    out.extend_from_slice(&header_json);
    out.extend_from_slice(body);
    out
}

/// Sends one request over the pooled client. The URL, method and headers
/// come as invoke headers (`x-vf-url`, `x-vf-method`, `x-vf-headers` as a
/// JSON list of pairs); the body is the raw invoke body.
#[tauri::command]
pub async fn openai_fetch(
    request: tauri::ipc::Request<'_>,
) -> Result<tauri::ipc::Response, String> {
    let meta = request.headers();
    let get = |name: &str| {
        meta.get(name)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string)
            .ok_or_else(|| format!("missing {name}"))
    };
    let url = get("x-vf-url")?;
    let method = get("x-vf-method")?;
    let pairs: Vec<(String, String)> =
        serde_json::from_str(&get("x-vf-headers")?).map_err(|e| format!("bad headers: {e}"))?;
    let body = match request.body() {
        tauri::ipc::InvokeBody::Raw(bytes) => bytes.clone(),
        tauri::ipc::InvokeBody::Json(_) => Vec::new(),
    };
    let framed = forward(&url, &method, &pairs, body).await?;
    Ok(tauri::ipc::Response::new(framed))
}

/// Sends one request to the OpenAI API over the pooled client and returns
/// the framed reply (`frame_response`). Refuses any other URL.
pub async fn forward(
    url: &str,
    method: &str,
    pairs: &[(String, String)],
    body: Vec<u8>,
) -> Result<Vec<u8>, String> {
    if !is_openai_url(url) {
        return Err("only api.openai.com is allowed here".into());
    }
    let method =
        reqwest::Method::from_bytes(method.as_bytes()).map_err(|_| "bad method".to_string())?;
    let mut builder = client().request(method, url);
    for (name, value) in pairs {
        // The transport sets these itself.
        let lower = name.to_ascii_lowercase();
        if matches!(lower.as_str(), "host" | "content-length" | "connection") {
            continue;
        }
        builder = builder.header(name.as_str(), value.as_str());
    }
    if !body.is_empty() {
        builder = builder.body(body);
    }
    let response = builder.send().await.map_err(|e| e.to_string())?;
    let status = response.status().as_u16();
    let headers: Vec<(String, String)> = response
        .headers()
        .iter()
        .filter_map(|(name, value)| {
            value
                .to_str()
                .ok()
                .map(|v| (name.as_str().to_string(), v.to_string()))
        })
        .collect();
    let bytes = response.bytes().await.map_err(|e| e.to_string())?;
    Ok(frame_response(status, &headers, &bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_openai_api_is_allowed() {
        assert!(is_openai_url("https://api.openai.com/v1/audio/transcriptions"));
        assert!(is_openai_url("https://api.openai.com/v1/chat/completions"));
        for bad in [
            "http://api.openai.com/v1/models",
            "https://api.openai.com.evil.test/v1/models",
            "https://evil.test/https://api.openai.com/v1",
            "https://api.openai.com:8443/v1/models",
            "https://user@api.openai.com/v1/models",
            "https://API.openai.com/v1/models",
            "file:///C:/Windows/win.ini",
            "not a url",
        ] {
            assert!(!is_openai_url(bad), "{bad}");
        }
    }

    #[tokio::test]
    async fn other_hosts_are_refused_before_any_request() {
        let refused = forward("https://example.com/v1/models", "GET", &[], Vec::new()).await;
        assert_eq!(refused.unwrap_err(), "only api.openai.com is allowed here");
    }

    /// Live: warms the pool, then sends a tiny tidy-up over it. Needs
    /// VF_TEST_OPENAI_KEY in the environment (memory only); prints status
    /// and timings, never the text. Run by hand with `--ignored`.
    #[tokio::test]
    #[ignore]
    async fn live_pooled_forward() {
        let Ok(key) = std::env::var("VF_TEST_OPENAI_KEY") else {
            println!("no key");
            return;
        };
        let t = std::time::Instant::now();
        let warmed = openai_warm().await;
        let warm_ms = t.elapsed().as_millis();
        let body = br#"{"model":"gpt-5.4-mini","messages":[{"role":"user","content":"Reply with OK."}]}"#.to_vec();
        let pairs = vec![
            ("authorization".to_string(), format!("Bearer {key}")),
            ("content-type".to_string(), "application/json".to_string()),
        ];
        for round in 0..2 {
            let t = std::time::Instant::now();
            let framed = forward("https://api.openai.com/v1/chat/completions", "POST", &pairs, body.clone())
                .await
                .expect("request");
            let status = u16::from_le_bytes([framed[0], framed[1]]);
            println!("round={round} status={status} ms={}", t.elapsed().as_millis());
        }
        println!("warmed={warmed} warm_ms={warm_ms}");
    }

    #[test]
    fn response_frame_layout() {
        let framed = frame_response(201, &[("a".into(), "b".into())], b"xyz");
        assert_eq!(u16::from_le_bytes([framed[0], framed[1]]), 201);
        let len = u32::from_le_bytes([framed[2], framed[3], framed[4], framed[5]]) as usize;
        assert_eq!(&framed[6..6 + len], br#"[["a","b"]]"#);
        assert_eq!(&framed[6 + len..], b"xyz");
    }
}
