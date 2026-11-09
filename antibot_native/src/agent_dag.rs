//! Native Multi-Agent Directed Acyclic Graph (DAG) & Consensus Engine.
//!
//! Provides:
//! - `validate_and_schedule_dag`: Topological sort, cycle detection, ready task discovery,
//!   and layered node visual coordinates (depth, lane) for 120 FPS Compose rendering.
//! - `evaluate_agent_consensus`: Multi-agent voting and confidence-weighted aggregation.

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AgentTaskNode {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub assigned_role: String,
    #[serde(default)]
    pub dependencies: Vec<String>,
    #[serde(default = "default_status")]
    pub status: String, // "pending", "running", "completed", "failed", "blocked"
    #[serde(default)]
    pub priority: i32,
}

fn default_status() -> String {
    "pending".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AgentNodeLayout {
    pub task_id: String,
    pub depth: usize,
    pub lane: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AgentDagResult {
    pub is_valid: bool,
    pub has_cycle: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cycle_path: Option<Vec<String>>,
    pub topological_order: Vec<String>,
    pub ready_tasks: Vec<String>,
    pub node_layouts: Vec<AgentNodeLayout>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AgentProposal {
    pub agent_id: String,
    pub role: String,
    pub value: String,
    #[serde(default = "default_confidence")]
    pub confidence: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rationale: Option<String>,
}

fn default_confidence() -> f64 {
    1.0
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ConsensusStrategy {
    Majority,
    Unanimous,
    ConfidenceWeighted,
}

impl ConsensusStrategy {
    pub fn parse(s: &str) -> Self {
        match s.to_lowercase().as_str() {
            "unanimous" => Self::Unanimous,
            "confidence_weighted" | "weighted" => Self::ConfidenceWeighted,
            _ => Self::Majority,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Majority => "majority",
            Self::Unanimous => "unanimous",
            Self::ConfidenceWeighted => "confidence_weighted",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ConsensusEvaluation {
    pub strategy: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub winner: Option<String>,
    pub is_consensus_reached: bool,
    pub agreement_ratio: f64,
    pub total_votes: usize,
    pub vote_tallies: HashMap<String, usize>,
    pub confidence_tallies: HashMap<String, f64>,
}

// ---------------------------------------------------------------------------
// 1. DAG Validation, Topological Sort & Layout
// ---------------------------------------------------------------------------

pub fn validate_and_schedule_dag(nodes: &[AgentTaskNode]) -> AgentDagResult {
    let mut in_degree: HashMap<String, usize> = HashMap::new();
    let mut adjacency: HashMap<String, Vec<String>> = HashMap::new();
    let mut node_map: HashMap<String, &AgentTaskNode> = HashMap::new();

    for node in nodes {
        node_map.insert(node.id.clone(), node);
        in_degree.entry(node.id.clone()).or_insert(0);
        adjacency.entry(node.id.clone()).or_default();
    }

    // Build edges (dep -> dependent)
    for node in nodes {
        for dep in &node.dependencies {
            if in_degree.contains_key(dep) {
                adjacency.entry(dep.clone()).or_default().push(node.id.clone());
                *in_degree.entry(node.id.clone()).or_insert(0) += 1;
            }
        }
    }

    // Kahn's algorithm for topological sorting and cycle detection
    let mut queue: VecDeque<String> = VecDeque::new();
    for (id, &deg) in &in_degree {
        if deg == 0 {
            queue.push_back(id.clone());
        }
    }

    let mut topological_order = Vec::new();
    while let Some(u) = queue.pop_front() {
        topological_order.push(u.clone());
        if let Some(neighbors) = adjacency.get(&u) {
            for v in neighbors {
                if let Some(deg) = in_degree.get_mut(v) {
                    *deg -= 1;
                    if *deg == 0 {
                        queue.push_back(v.clone());
                    }
                }
            }
        }
    }

    let has_cycle = topological_order.len() != nodes.len();
    let is_valid = !has_cycle;

    let cycle_path = if has_cycle {
        // Collect nodes with remaining non-zero in-degree
        let cycle_nodes: Vec<String> = in_degree
            .into_iter()
            .filter(|(_, deg)| *deg > 0)
            .map(|(id, _)| id)
            .collect();
        Some(cycle_nodes)
    } else {
        None
    };

    // Ready tasks: status == "pending", and all dependencies have status == "completed"
    let mut ready_tasks = Vec::new();
    for node in nodes {
        if node.status == "pending" {
            let all_deps_completed = node.dependencies.iter().all(|dep_id| {
                node_map
                    .get(dep_id)
                    .map(|d| d.status == "completed")
                    .unwrap_or(false)
            });
            if all_deps_completed {
                ready_tasks.push(node.id.clone());
            }
        }
    }

    // Calculate layout coordinates (depth and lane)
    let mut depth_map: HashMap<String, usize> = HashMap::new();
    for id in &topological_order {
        if let Some(node) = node_map.get(id) {
            let max_dep_depth = node
                .dependencies
                .iter()
                .filter_map(|d| depth_map.get(d))
                .max()
                .copied()
                .unwrap_or(0);
            let depth = if node.dependencies.is_empty() {
                0
            } else {
                max_dep_depth + 1
            };
            depth_map.insert(id.clone(), depth);
        }
    }

    let mut lane_counters: HashMap<usize, usize> = HashMap::new();
    let mut node_layouts = Vec::new();
    for id in &topological_order {
        let depth = *depth_map.get(id).unwrap_or(&0);
        let lane = *lane_counters.get(&depth).unwrap_or(&0);
        lane_counters.insert(depth, lane + 1);

        node_layouts.push(AgentNodeLayout {
            task_id: id.clone(),
            depth,
            lane,
        });
    }

    AgentDagResult {
        is_valid,
        has_cycle,
        cycle_path,
        topological_order,
        ready_tasks,
        node_layouts,
    }
}

// ---------------------------------------------------------------------------
// 2. Multi-Agent Consensus Evaluation
// ---------------------------------------------------------------------------

pub fn evaluate_agent_consensus(
    proposals: &[AgentProposal],
    strategy: ConsensusStrategy,
) -> ConsensusEvaluation {
    let total_votes = proposals.len();
    if total_votes == 0 {
        return ConsensusEvaluation {
            strategy: strategy.as_str().to_string(),
            winner: None,
            is_consensus_reached: false,
            agreement_ratio: 0.0,
            total_votes: 0,
            vote_tallies: HashMap::new(),
            confidence_tallies: HashMap::new(),
        };
    }

    let mut vote_tallies: HashMap<String, usize> = HashMap::new();
    let mut confidence_tallies: HashMap<String, f64> = HashMap::new();
    let mut total_confidence = 0.0;

    for prop in proposals {
        *vote_tallies.entry(prop.value.clone()).or_insert(0) += 1;
        let conf = prop.confidence.max(0.0).min(1.0);
        *confidence_tallies.entry(prop.value.clone()).or_insert(0.0) += conf;
        total_confidence += conf;
    }

    match strategy {
        ConsensusStrategy::Majority => {
            let mut best_val = None;
            let mut max_votes = 0;
            for (val, &votes) in &vote_tallies {
                if votes > max_votes {
                    max_votes = votes;
                    best_val = Some(val.clone());
                }
            }
            let ratio = max_votes as f64 / total_votes as f64;
            let reached = ratio > 0.5;
            ConsensusEvaluation {
                strategy: strategy.as_str().to_string(),
                winner: if reached { best_val } else { None },
                is_consensus_reached: reached,
                agreement_ratio: (ratio * 1000.0).round() / 1000.0,
                total_votes,
                vote_tallies,
                confidence_tallies,
            }
        }
        ConsensusStrategy::Unanimous => {
            let is_unanimous = vote_tallies.len() == 1;
            let winner = if is_unanimous {
                vote_tallies.keys().next().cloned()
            } else {
                None
            };
            let ratio = if is_unanimous { 1.0 } else { 0.0 };
            ConsensusEvaluation {
                strategy: strategy.as_str().to_string(),
                winner,
                is_consensus_reached: is_unanimous,
                agreement_ratio: ratio,
                total_votes,
                vote_tallies,
                confidence_tallies,
            }
        }
        ConsensusStrategy::ConfidenceWeighted => {
            let mut best_val = None;
            let mut max_weight = 0.0;
            for (val, &weight) in &confidence_tallies {
                if weight > max_weight {
                    max_weight = weight;
                    best_val = Some(val.clone());
                }
            }
            let ratio = if total_confidence > 0.0 {
                max_weight / total_confidence
            } else {
                0.0
            };
            let reached = ratio > 0.5;
            ConsensusEvaluation {
                strategy: strategy.as_str().to_string(),
                winner: if reached { best_val } else { None },
                is_consensus_reached: reached,
                agreement_ratio: (ratio * 1000.0).round() / 1000.0,
                total_votes,
                vote_tallies,
                confidence_tallies,
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_dag_linear_execution_and_ready() {
        let nodes = vec![
            AgentTaskNode {
                id: "t1".to_string(),
                title: "Research API".to_string(),
                assigned_role: "researcher".to_string(),
                dependencies: vec![],
                status: "completed".to_string(),
                priority: 10,
            },
            AgentTaskNode {
                id: "t2".to_string(),
                title: "Implement Feature".to_string(),
                assigned_role: "coder".to_string(),
                dependencies: vec!["t1".to_string()],
                status: "pending".to_string(),
                priority: 20,
            },
            AgentTaskNode {
                id: "t3".to_string(),
                title: "Write Tests".to_string(),
                assigned_role: "tester".to_string(),
                dependencies: vec!["t2".to_string()],
                status: "pending".to_string(),
                priority: 30,
            },
        ];

        let res = validate_and_schedule_dag(&nodes);
        assert!(res.is_valid);
        assert!(!res.has_cycle);
        assert_eq!(res.topological_order, vec!["t1", "t2", "t3"]);
        // t2 is ready because t1 is completed; t3 is not ready because t2 is still pending
        assert_eq!(res.ready_tasks, vec!["t2"]);

        // Check depth layouts
        let layout_t1 = res.node_layouts.iter().find(|l| l.task_id == "t1").unwrap();
        assert_eq!(layout_t1.depth, 0);
        let layout_t2 = res.node_layouts.iter().find(|l| l.task_id == "t2").unwrap();
        assert_eq!(layout_t2.depth, 1);
        let layout_t3 = res.node_layouts.iter().find(|l| l.task_id == "t3").unwrap();
        assert_eq!(layout_t3.depth, 2);
    }

    #[test]
    fn test_dag_cycle_detection() {
        let nodes = vec![
            AgentTaskNode {
                id: "a".to_string(),
                title: "Task A".to_string(),
                assigned_role: "coder".to_string(),
                dependencies: vec!["b".to_string()],
                status: "pending".to_string(),
                priority: 1,
            },
            AgentTaskNode {
                id: "b".to_string(),
                title: "Task B".to_string(),
                assigned_role: "tester".to_string(),
                dependencies: vec!["a".to_string()],
                status: "pending".to_string(),
                priority: 1,
            },
        ];

        let res = validate_and_schedule_dag(&nodes);
        assert!(!res.is_valid);
        assert!(res.has_cycle);
        assert!(res.cycle_path.is_some());
    }

    #[test]
    fn test_consensus_majority() {
        let proposals = vec![
            AgentProposal {
                agent_id: "agent_1".to_string(),
                role: "architect".to_string(),
                value: "OptionA".to_string(),
                confidence: 0.9,
                rationale: None,
            },
            AgentProposal {
                agent_id: "agent_2".to_string(),
                role: "coder".to_string(),
                value: "OptionA".to_string(),
                confidence: 0.8,
                rationale: None,
            },
            AgentProposal {
                agent_id: "agent_3".to_string(),
                role: "reviewer".to_string(),
                value: "OptionB".to_string(),
                confidence: 0.6,
                rationale: None,
            },
        ];

        let res = evaluate_agent_consensus(&proposals, ConsensusStrategy::Majority);
        assert!(res.is_consensus_reached);
        assert_eq!(res.winner.as_deref(), Some("OptionA"));
        assert_eq!(res.total_votes, 3);
        assert_eq!(res.vote_tallies.get("OptionA"), Some(&2));
        assert_eq!(res.vote_tallies.get("OptionB"), Some(&1));
    }

    #[test]
    fn test_consensus_unanimous_failure() {
        let proposals = vec![
            AgentProposal {
                agent_id: "agent_1".to_string(),
                role: "coder".to_string(),
                value: "FixBug".to_string(),
                confidence: 1.0,
                rationale: None,
            },
            AgentProposal {
                agent_id: "agent_2".to_string(),
                role: "reviewer".to_string(),
                value: "Refactor".to_string(),
                confidence: 1.0,
                rationale: None,
            },
        ];

        let res = evaluate_agent_consensus(&proposals, ConsensusStrategy::Unanimous);
        assert!(!res.is_consensus_reached);
        assert!(res.winner.is_none());
    }

    #[test]
    fn test_consensus_confidence_weighted() {
        let proposals = vec![
            AgentProposal {
                agent_id: "agent_senior".to_string(),
                role: "lead".to_string(),
                value: "RustEngine".to_string(),
                confidence: 0.95,
                rationale: None,
            },
            AgentProposal {
                agent_id: "agent_junior_1".to_string(),
                role: "intern".to_string(),
                value: "PythonScript".to_string(),
                confidence: 0.2,
                rationale: None,
            },
            AgentProposal {
                agent_id: "agent_junior_2".to_string(),
                role: "intern".to_string(),
                value: "PythonScript".to_string(),
                confidence: 0.2,
                rationale: None,
            },
        ];

        // PythonScript has 2 votes vs 1 vote, but RustEngine has 0.95 weight vs 0.40 total
        let res = evaluate_agent_consensus(&proposals, ConsensusStrategy::ConfidenceWeighted);
        assert!(res.is_consensus_reached);
        assert_eq!(res.winner.as_deref(), Some("RustEngine"));
    }
}
