use axum::{http::StatusCode, Json};
use serde::Deserialize;
use antibot_native::agent_dag::{
    self, AgentDagResult, AgentProposal, AgentTaskNode, ConsensusEvaluation,
    ConsensusStrategy,
};

#[derive(Debug, Deserialize)]
#[serde(untagged)]
pub enum DagScheduleRequest {
    Object {
        nodes: Vec<AgentTaskNode>,
    },
    Array(Vec<AgentTaskNode>),
}

#[derive(Debug, Deserialize)]
pub struct ConsensusRequest {
    pub proposals: Vec<AgentProposal>,
    #[serde(default = "default_strategy")]
    pub strategy: String,
}

fn default_strategy() -> String {
    "majority".to_string()
}

/// POST /api/agents/dag
///
/// Validates dependencies, performs Kahn's topological sort, detects cycles,
/// identifies ready unblocked tasks, and computes Compose depth/lane coordinates.
pub async fn dag_schedule_handler(
    Json(payload): Json<DagScheduleRequest>,
) -> (StatusCode, Json<AgentDagResult>) {
    let nodes = match payload {
        DagScheduleRequest::Object { nodes } => nodes,
        DagScheduleRequest::Array(nodes) => nodes,
    };

    let result = agent_dag::validate_and_schedule_dag(&nodes);
    (StatusCode::OK, Json(result))
}

/// POST /api/agents/consensus
///
/// Evaluates multi-agent proposals using majority, unanimous, or confidence-weighted voting.
pub async fn consensus_handler(
    Json(payload): Json<ConsensusRequest>,
) -> (StatusCode, Json<ConsensusEvaluation>) {
    let strategy = ConsensusStrategy::parse(&payload.strategy);
    let result = agent_dag::evaluate_agent_consensus(&payload.proposals, strategy);
    (StatusCode::OK, Json(result))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_agent_dag_handler() {
        let req = DagScheduleRequest::Object {
            nodes: vec![
                AgentTaskNode {
                    id: "plan".to_string(),
                    title: "Plan feature".to_string(),
                    assigned_role: "architect".to_string(),
                    dependencies: vec![],
                    status: "completed".to_string(),
                    priority: 1,
                },
                AgentTaskNode {
                    id: "code".to_string(),
                    title: "Write code".to_string(),
                    assigned_role: "coder".to_string(),
                    dependencies: vec!["plan".to_string()],
                    status: "pending".to_string(),
                    priority: 2,
                },
            ],
        };

        let (status, Json(res)) = dag_schedule_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        assert!(res.is_valid);
        assert_eq!(res.topological_order, vec!["plan", "code"]);
        assert_eq!(res.ready_tasks, vec!["code"]);
    }

    #[tokio::test]
    async fn test_agent_consensus_handler() {
        let req = ConsensusRequest {
            proposals: vec![
                AgentProposal {
                    agent_id: "a1".to_string(),
                    role: "coder".to_string(),
                    value: "approach_b".to_string(),
                    confidence: 0.9,
                    rationale: Some("Fastest".to_string()),
                },
                AgentProposal {
                    agent_id: "a2".to_string(),
                    role: "reviewer".to_string(),
                    value: "approach_b".to_string(),
                    confidence: 0.85,
                    rationale: Some("Safe".to_string()),
                },
                AgentProposal {
                    agent_id: "a3".to_string(),
                    role: "tester".to_string(),
                    value: "approach_a".to_string(),
                    confidence: 0.6,
                    rationale: None,
                },
            ],
            strategy: "confidence_weighted".to_string(),
        };

        let (status, Json(res)) = consensus_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        assert!(res.is_consensus_reached);
        assert_eq!(res.winner, Some("approach_b".to_string()));
    }
}
