package com.example.gemini.data.skills

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object AgenticSkillsMcp {
    const val INSTRUCTIONS = """
    Agentic Awesome Skills (AAS): Access 2,955+ reusable, standardized AI agent skills.
    Use search_skills to discover relevant skills for any development, architecture, testing, security, or creative task.
    Use get_skill to read the full SKILL.md playbook before starting work.
    Use install_skill or install_bundle to persist skills into the project (.agents/skills) or globally.
    """

    private val HANDLED_TOOLS = setOf(
        "search_skills",
        "get_skill",
        "list_skill_files",
        "read_skill_file",
        "install_skill",
        "list_installed_skills",
        "list_bundles",
        "install_bundle"
    )

    fun handles(toolName: String): Boolean = HANDLED_TOOLS.contains(toolName)

    fun tools(): JSONArray {
        val tools = JSONArray()

        // 1. search_skills
        tools.put(JSONObject().apply {
            put("name", "search_skills")
            put("description", "Search the library of 2,955+ agentic skills by keyword, category, or risk rating.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "Keywords to search for (e.g., 'debugging', 'android', 'rust', 'auth')")
                    })
                    put("category", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional category filter (e.g., 'workflow', 'security', 'development', 'testing')")
                    })
                    put("risk", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional risk filter ('safe', 'low', 'medium', 'critical')")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum results to return (default: 20)")
                    })
                })
                put("required", JSONArray().apply { put("query") })
            })
        })

        // 2. get_skill
        tools.put(JSONObject().apply {
            put("name", "get_skill")
            put("description", "Retrieve the complete SKILL.md instructions, operating mode, and rules for a specific skill ID.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply {
                        put("type", "string")
                        put("description", "The unique skill ID (e.g. 'brainstorming', 'systematic-debugging')")
                    })
                })
                put("required", JSONArray().apply { put("id") })
            })
        })

        // 3. list_skill_files
        tools.put(JSONObject().apply {
            put("name", "list_skill_files")
            put("description", "List auxiliary scripts, schemas, and reference documents inside a skill.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply {
                        put("type", "string")
                        put("description", "The skill ID")
                    })
                })
                put("required", JSONArray().apply { put("id") })
            })
        })

        // 4. read_skill_file
        tools.put(JSONObject().apply {
            put("name", "read_skill_file")
            put("description", "Read a specific auxiliary script or documentation file belonging to a skill.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply {
                        put("type", "string")
                        put("description", "The skill ID")
                    })
                    put("file_path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Relative path of file inside the skill folder")
                    })
                })
                put("required", JSONArray().apply {
                    put("id")
                    put("file_path")
                })
            })
        })

        // 5. install_skill
        tools.put(JSONObject().apply {
            put("name", "install_skill")
            put("description", "Install a skill into the active project workspace (.agents/skills) or global CLI storage.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply {
                        put("type", "string")
                        put("description", "The skill ID to install")
                    })
                    put("target", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("project")
                            put("gemini")
                            put("codex")
                            put("claude")
                            put("opencode")
                        })
                        put("description", "Where to install: 'project' (.agents/skills), 'gemini', 'codex', 'claude', or 'opencode'")
                    })
                    put("project_path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to project directory (required if target is 'project')")
                    })
                })
                put("required", JSONArray().apply {
                    put("id")
                    put("target")
                })
            })
        })

        // 6. list_installed_skills
        tools.put(JSONObject().apply {
            put("name", "list_installed_skills")
            put("description", "List skills currently installed in a project or global CLI location.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("target", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("project")
                            put("gemini")
                            put("codex")
                            put("claude")
                            put("opencode")
                        })
                    })
                    put("project_path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to project directory if target is 'project'")
                    })
                })
                put("required", JSONArray().apply { put("target") })
            })
        })

        // 7. list_bundles
        tools.put(JSONObject().apply {
            put("name", "list_bundles")
            put("description", "List all 30 curated domain bundles (e.g. 'essentials', 'architecture-design', 'mobile-app-builder', 'secure-app-builder').")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        })

        // 8. install_bundle
        tools.put(JSONObject().apply {
            put("name", "install_bundle")
            put("description", "Install an entire curated bundle of skills into a project or global location.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("bundle_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "The bundle name (e.g. 'essentials', 'mobile-app-builder')")
                    })
                    put("target", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("project")
                            put("gemini")
                            put("codex")
                            put("claude")
                            put("opencode")
                        })
                    })
                    put("project_path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Project directory path")
                    })
                })
                put("required", JSONArray().apply {
                    put("bundle_name")
                    put("target")
                })
            })
        })

        return tools
    }

    suspend fun execute(toolName: String, args: JSONObject): JSONObject {
        return when (toolName) {
            "search_skills" -> {
                val query = args.optString("query", "")
                val category = args.optString("category").takeIf { it.isNotBlank() }
                val risk = args.optString("risk").takeIf { it.isNotBlank() }
                val limit = args.optInt("limit", 20)

                val results = AgenticSkillsRepository.searchSkills(query, category, risk, limit)
                val arr = JSONArray()
                for (r in results) {
                    arr.put(JSONObject().apply {
                        put("id", r.id)
                        put("name", r.name)
                        put("category", r.category)
                        put("description", r.description)
                        put("risk", r.risk)
                        put("tags", JSONArray(r.tags))
                    })
                }

                makeContentResponse(JSONObject().apply {
                    put("count", results.size)
                    put("skills", arr)
                }.toString(2))
            }

            "get_skill" -> {
                val id = args.optString("id", "")
                if (id.isBlank()) return makeErrorResponse("id argument is required")

                val detail = AgenticSkillsRepository.getSkillDetail(id)
                    ?: return makeErrorResponse("Skill '$id' not found in catalog")

                makeContentResponse(JSONObject().apply {
                    put("id", detail.meta.id)
                    put("name", detail.meta.name)
                    put("category", detail.meta.category)
                    put("risk", detail.meta.risk)
                    put("description", detail.meta.description)
                    put("content", detail.content)
                    put("files", JSONArray(detail.files))
                }.toString(2))
            }

            "list_skill_files" -> {
                val id = args.optString("id", "")
                val detail = AgenticSkillsRepository.getSkillDetail(id)
                    ?: return makeErrorResponse("Skill '$id' not found")

                makeContentResponse(JSONObject().apply {
                    put("id", id)
                    put("files", JSONArray(detail.files))
                }.toString(2))
            }

            "read_skill_file" -> {
                val id = args.optString("id", "")
                val filePath = args.optString("file_path", "")
                val bDir = AgenticSkillsRepository.getLocalBaseDir()
                    ?: return makeErrorResponse("Local catalog directory not found")

                val file = File(bDir, "skills/$id/$filePath")
                if (!file.exists() || !file.isFile) {
                    return makeErrorResponse("File '$filePath' not found in skill '$id'")
                }

                makeContentResponse(file.readText(Charsets.UTF_8))
            }

            "install_skill" -> {
                val id = args.optString("id", "")
                val targetStr = args.optString("target", "project")
                val projectPath = args.optString("project_path").takeIf { it.isNotBlank() }

                val target = when (targetStr.lowercase()) {
                    "gemini" -> SkillInstallTarget.GEMINI
                    "codex" -> SkillInstallTarget.CODEX
                    "claude" -> SkillInstallTarget.CLAUDE
                    "opencode" -> SkillInstallTarget.OPENCODE
                    else -> SkillInstallTarget.PROJECT
                }

                val res = AgenticSkillsRepository.installSkill(id, target, projectPath)
                makeContentResponse(JSONObject().apply {
                    put("success", res.success)
                    put("message", res.message)
                    put("installedPath", res.installedPath)
                    put("error", res.error)
                }.toString(2))
            }

            "list_installed_skills" -> {
                val targetStr = args.optString("target", "project")
                val projectPath = args.optString("project_path").takeIf { it.isNotBlank() }

                val targetDir = when (targetStr.lowercase()) {
                    "gemini" -> File("/data/user/0/com.termux/files/home/.gemini/config/skills")
                    "codex" -> File("/data/user/0/com.termux/files/home/.codex/skills")
                    "claude" -> File("/data/user/0/com.termux/files/home/.claude/skills")
                    "opencode" -> File("/data/user/0/com.termux/files/home/.opencode/skills")
                    else -> if (!projectPath.isNullOrBlank()) File(projectPath, ".agents/skills") else File(".")
                }

                val installed = AgenticSkillsRepository.listInstalled(targetDir)
                makeContentResponse(JSONObject().apply {
                    put("target", targetStr)
                    put("path", targetDir.absolutePath)
                    put("count", installed.size)
                    put("skills", JSONArray(installed))
                }.toString(2))
            }

            "list_bundles" -> {
                val bundles = AgenticSkillsRepository.listBundles()
                val arr = JSONArray()
                for (b in bundles) {
                    arr.put(JSONObject().apply {
                        put("name", b.name)
                        put("displayName", b.displayName)
                        put("description", b.description)
                        put("skillCount", b.skillCount)
                        put("skills", JSONArray(b.skills))
                    })
                }
                makeContentResponse(JSONObject().apply {
                    put("count", bundles.size)
                    put("bundles", arr)
                }.toString(2))
            }

            "install_bundle" -> {
                val bundleName = args.optString("bundle_name", "")
                val targetStr = args.optString("target", "project")
                val projectPath = args.optString("project_path").takeIf { it.isNotBlank() }

                val target = when (targetStr.lowercase()) {
                    "gemini" -> SkillInstallTarget.GEMINI
                    "codex" -> SkillInstallTarget.CODEX
                    "claude" -> SkillInstallTarget.CLAUDE
                    "opencode" -> SkillInstallTarget.OPENCODE
                    else -> SkillInstallTarget.PROJECT
                }

                val res = AgenticSkillsRepository.installBundle(bundleName, target, projectPath)
                makeContentResponse(JSONObject().apply {
                    put("success", res.success)
                    put("message", res.message)
                }.toString(2))
            }

            else -> makeErrorResponse("Unknown skill tool: $toolName")
        }
    }

    private fun makeContentResponse(text: String): JSONObject {
        return JSONObject().apply {
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", text)
                })
            })
        }
    }

    private fun makeErrorResponse(error: String): JSONObject {
        return JSONObject().apply {
            put("isError", true)
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "Error: $error")
                })
            })
        }
    }
}
