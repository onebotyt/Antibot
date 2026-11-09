use axum::{
    extract::Query,
    http::StatusCode,
    Json,
};
use serde::{Deserialize, Serialize};
use antibot_native::hw_telemetry::{
    collect_hardware_telemetry, evaluate_thermal_throttle, CpuCoreInfo, HardwareTelemetry,
    ThrottleAdvice,
};

#[derive(Debug, Deserialize)]
pub struct TelemetryQueryParams {
    #[serde(default)]
    pub override_temp: Option<f32>,
}

#[derive(Debug, Deserialize)]
pub struct ThermalEvalRequest {
    pub temp_celsius: f32,
    #[serde(default = "default_total_cores")]
    pub total_cores: usize,
}

fn default_total_cores() -> usize {
    8
}

#[derive(Debug, Serialize)]
pub struct CpuSummaryResponse {
    pub online_cores_count: usize,
    pub avg_cpu_freq_khz: u64,
    pub max_cpu_freq_khz: u64,
    pub cores: Vec<CpuCoreInfo>,
}

/// GET /api/hw/telemetry
///
/// Returns real-time hardware telemetry: CPU topology/frequencies, RAM metrics,
/// thermal zone sensors, and adaptive throttling advice.
pub async fn hw_telemetry_handler(
    Query(params): Query<TelemetryQueryParams>,
) -> (StatusCode, Json<HardwareTelemetry>) {
    let telem = collect_hardware_telemetry(params.override_temp);
    (StatusCode::OK, Json(telem))
}

/// POST /api/hw/thermal/evaluate
///
/// Evaluates given device temperature and advises worker threads and token pacing.
pub async fn hw_thermal_eval_handler(
    Json(payload): Json<ThermalEvalRequest>,
) -> (StatusCode, Json<ThrottleAdvice>) {
    let advice = evaluate_thermal_throttle(payload.temp_celsius, payload.total_cores);
    (StatusCode::OK, Json(advice))
}

/// GET /api/hw/cpu
///
/// Returns CPU core topology and operating frequencies.
pub async fn hw_cpu_handler() -> (StatusCode, Json<CpuSummaryResponse>) {
    let telem = collect_hardware_telemetry(None);
    (
        StatusCode::OK,
        Json(CpuSummaryResponse {
            online_cores_count: telem.online_cores_count,
            avg_cpu_freq_khz: telem.avg_cpu_freq_khz,
            max_cpu_freq_khz: telem.max_cpu_freq_khz,
            cores: telem.cpu_cores,
        }),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use antibot_native::hw_telemetry::ThermalSeverity;

    #[tokio::test]
    async fn test_hw_handlers_lifecycle() {
        // 1. GET telemetry with override temperature
        let (status, Json(telem)) = hw_telemetry_handler(Query(TelemetryQueryParams {
            override_temp: Some(45.0),
        }))
        .await;
        assert_eq!(status, StatusCode::OK);
        assert!(telem.online_cores_count >= 1);
        assert_eq!(telem.max_temperature_celsius, 45.0);
        assert_eq!(telem.throttle_advice.severity, ThermalSeverity::Warm);

        // 2. POST thermal eval
        let (status, Json(advice)) = hw_thermal_eval_handler(Json(ThermalEvalRequest {
            temp_celsius: 70.0,
            total_cores: 8,
        }))
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(advice.severity, ThermalSeverity::Critical);
        assert_eq!(advice.recommended_threads, 1);
        assert_eq!(advice.throttle_multiplier, 0.1);

        // 3. GET CPU summary
        let (status, Json(cpu)) = hw_cpu_handler().await;
        assert_eq!(status, StatusCode::OK);
        assert!(cpu.online_cores_count >= 1);
    }
}
