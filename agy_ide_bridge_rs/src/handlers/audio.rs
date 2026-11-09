use axum::{
    extract::ws::{Message, WebSocket, WebSocketUpgrade},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde::Deserialize;
use std::sync::Mutex;
use antibot_native::audio_dsp::{self, AudioTelemetry};

static NOISE_FLOOR: Mutex<f32> = Mutex::new(300.0);

#[derive(Debug, Deserialize)]
pub struct AudioTelemetryRequest {
    pub pcm_base64: Option<String>,
    pub pcm_hex: Option<String>,
    #[serde(default = "default_sample_rate")]
    pub sample_rate: u32,
    #[serde(default = "default_num_bands")]
    pub num_bands: usize,
}

fn default_sample_rate() -> u32 {
    16000
}

fn default_num_bands() -> usize {
    8
}

fn decode_pcm_bytes(payload: &AudioTelemetryRequest) -> Vec<u8> {
    if let Some(b64) = &payload.pcm_base64 {
        // Base64 decoding fallback using standard simple base64 decoder
        let clean = b64.trim().replace('\n', "").replace('\r', "");
        if let Ok(bytes) = base64_decode(&clean) {
            return bytes;
        }
    }

    if let Some(hex) = &payload.pcm_hex {
        let clean = hex.trim();
        let mut bytes = Vec::with_capacity(clean.len() / 2);
        for chunk in clean.as_bytes().chunks(2) {
            if chunk.len() == 2 {
                if let (Ok(hi), Ok(lo)) = (
                    hex_val(chunk[0]),
                    hex_val(chunk[1]),
                ) {
                    bytes.push((hi << 4) | lo);
                }
            }
        }
        return bytes;
    }

    Vec::new()
}

fn hex_val(b: u8) -> Result<u8, ()> {
    match b {
        b'0'..=b'9' => Ok(b - b'0'),
        b'a'..=b'f' => Ok(b - b'a' + 10),
        b'A'..=b'F' => Ok(b - b'A' + 10),
        _ => Err(()),
    }
}

fn base64_decode(input: &str) -> Result<Vec<u8>, ()> {
    let mut out = Vec::new();
    let mut buf = 0u32;
    let mut bits = 0u32;

    for b in input.bytes() {
        let val = match b {
            b'A'..=b'Z' => b - b'A',
            b'a'..=b'z' => b - b'a' + 26,
            b'0'..=b'9' => b - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => continue,
            _ => continue,
        };

        buf = (buf << 6) | (val as u32);
        bits += 6;

        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
            buf &= (1 << bits) - 1;
        }
    }

    Ok(out)
}

/// POST /api/audio/telemetry
///
/// Computes real-time audio telemetry (RMS, SNR, speech detection, dominant pitch frequency,
/// and multi-band equalizer spectrum) from raw PCM audio samples.
pub async fn audio_telemetry_handler(
    Json(payload): Json<AudioTelemetryRequest>,
) -> (StatusCode, Json<AudioTelemetry>) {
    let pcm = decode_pcm_bytes(&payload);
    let mut noise = NOISE_FLOOR.lock().unwrap();

    let telemetry = audio_dsp::analyze_pcm_telemetry(
        &pcm,
        payload.sample_rate,
        payload.num_bands,
        &mut noise,
    );

    (StatusCode::OK, Json(telemetry))
}

/// GET /ws/audio/telemetry
///
/// WebSocket endpoint streaming live audio telemetry packets for Jetpack Compose UI waveforms.
pub async fn ws_audio_telemetry_handler(
    ws: WebSocketUpgrade,
) -> impl IntoResponse {
    ws.on_upgrade(handle_audio_telemetry_socket)
}

async fn handle_audio_telemetry_socket(mut socket: WebSocket) {
    let mut local_noise = 300.0f32;

    while let Some(msg) = socket.recv().await {
        let msg = match msg {
            Ok(m) => m,
            Err(_) => break,
        };

        match msg {
            Message::Binary(bin) => {
                let telemetry = audio_dsp::analyze_pcm_telemetry(&bin, 16000, 8, &mut local_noise);
                if let Ok(json_str) = serde_json::to_string(&telemetry) {
                    if socket.send(Message::Text(json_str)).await.is_err() {
                        break;
                    }
                }
            }
            Message::Text(txt) => {
                if let Ok(req) = serde_json::from_str::<AudioTelemetryRequest>(&txt) {
                    let pcm = decode_pcm_bytes(&req);
                    let telemetry = audio_dsp::analyze_pcm_telemetry(
                        &pcm,
                        req.sample_rate,
                        req.num_bands,
                        &mut local_noise,
                    );
                    if let Ok(json_str) = serde_json::to_string(&telemetry) {
                        if socket.send(Message::Text(json_str)).await.is_err() {
                            break;
                        }
                    }
                }
            }
            Message::Ping(payload) => {
                if socket.send(Message::Pong(payload)).await.is_err() {
                    break;
                }
            }
            Message::Close(_) => break,
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_audio_telemetry_handler_empty() {
        let req = AudioTelemetryRequest {
            pcm_base64: None,
            pcm_hex: None,
            sample_rate: 16000,
            num_bands: 8,
        };

        let (status, Json(res)) = audio_telemetry_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.spectrum_bands.len(), 8);
        assert!(!res.is_speech);
        assert_eq!(res.sample_rate, 16000);
    }

    #[tokio::test]
    async fn test_audio_telemetry_handler_hex() {
        // 2 samples of i16: 1000 (0x03e8), -1000 (0xfc18)
        let req = AudioTelemetryRequest {
            pcm_base64: None,
            pcm_hex: Some("e80318fc".to_string()),
            sample_rate: 16000,
            num_bands: 8,
        };

        let (status, Json(res)) = audio_telemetry_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.spectrum_bands.len(), 8);
        assert!(res.peak_amplitude > 0.0);
    }
}
