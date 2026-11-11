package handlers

import (
	"encoding/json"
	"io/ioutil"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"gemini-server/pkg/config"
)

type SkillMetaJSON struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	Category    string   `json:"category"`
	Description string   `json:"description"`
	Risk        string   `json:"risk"`
	Source      string   `json:"source"`
	Tags        []string `json:"tags"`
	Path        string   `json:"path"`
}

type SkillInstallReq struct {
	ID          string `json:"id"`
	Target      string `json:"target"`
	ProjectPath string `json:"projectPath"`
}

func getSkillsBaseDir() string {
	candidates := []string{
		config.ExpandHome("~/.agentic-awesome-skills"),
		"/data/user/0/com.termux/files/home/.agentic-awesome-skills",
		"/data/data/com.termux/files/home/.agentic-awesome-skills",
	}
	for _, c := range candidates {
		if fi, err := os.Stat(filepath.Join(c, "skills")); err == nil && fi.IsDir() {
			return c
		}
	}
	return ""
}

func (h *Handler) SkillsSearchHandler(w http.ResponseWriter, r *http.Request) {
	q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	cat := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("category")))
	risk := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("risk")))
	limitStr := r.URL.Query().Get("limit")
	limit := 30
	if l, err := strconv.Atoi(limitStr); err == nil && l > 0 {
		limit = l
	}

	baseDir := getSkillsBaseDir()
	if baseDir == "" {
		writeJSON(w, http.StatusOK, map[string]interface{}{"count": 0, "skills": []SkillMetaJSON{}})
		return
	}

	indexPath := filepath.Join(baseDir, "skills_index.json")
	data, err := ioutil.ReadFile(indexPath)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"count": 0, "skills": []SkillMetaJSON{}})
		return
	}

	var allSkills []SkillMetaJSON
	if err := json.Unmarshal(data, &allSkills); err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"count": 0, "skills": []SkillMetaJSON{}})
		return
	}

	qTokens := strings.Fields(q)
	var matched []SkillMetaJSON

	for _, s := range allSkills {
		if cat != "" && cat != "all" && strings.ToLower(s.Category) != cat {
			continue
		}
		if risk != "" && risk != "all" && strings.ToLower(s.Risk) != risk {
			continue
		}

		if len(qTokens) > 0 {
			targetText := strings.ToLower(s.ID + " " + s.Name + " " + s.Description + " " + s.Category + " " + strings.Join(s.Tags, " "))
			match := true
			for _, tok := range qTokens {
				if !strings.Contains(targetText, tok) {
					match = false
					break
				}
			}
			if !match {
				continue
			}
		}

		matched = append(matched, s)
		if len(matched) >= limit {
			break
		}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"count":  len(matched),
		"skills": matched,
	})
}

func (h *Handler) SkillsGetHandler(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimSpace(r.URL.Query().Get("id"))
	if id == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"error": "id parameter required"})
		return
	}

	baseDir := getSkillsBaseDir()
	if baseDir == "" {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"error": "skills base directory not found"})
		return
	}

	skillFile := filepath.Join(baseDir, "skills", id, "SKILL.md")
	content, err := ioutil.ReadFile(skillFile)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"error": "skill not found"})
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"id":      id,
		"content": string(content),
	})
}

func (h *Handler) SkillsBundlesHandler(w http.ResponseWriter, r *http.Request) {
	baseDir := getSkillsBaseDir()
	if baseDir == "" {
		writeJSON(w, http.StatusOK, map[string]interface{}{"bundles": []interface{}{}})
		return
	}

	pluginsDir := filepath.Join(baseDir, "plugins")
	entries, err := ioutil.ReadDir(pluginsDir)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"bundles": []interface{}{}})
		return
	}

	type BundleInfo struct {
		Name        string   `json:"name"`
		DisplayName string   `json:"displayName"`
		SkillCount  int      `json:"skillCount"`
		Skills      []string `json:"skills"`
	}

	var bundles []BundleInfo
	for _, entry := range entries {
		if entry.IsDir() && strings.HasPrefix(entry.Name(), "agentic-bundle-") {
			rawName := strings.TrimPrefix(entry.Name(), "agentic-bundle-")
			skillsSub := filepath.Join(pluginsDir, entry.Name(), "skills")
			var skillNames []string
			if subEntries, err := ioutil.ReadDir(skillsSub); err == nil {
				for _, se := range subEntries {
					if se.IsDir() {
						skillNames = append(skillNames, se.Name())
					}
				}
			}
			bundles = append(bundles, BundleInfo{
				Name:        rawName,
				DisplayName: strings.ReplaceAll(rawName, "-", " "),
				SkillCount:  len(skillNames),
				Skills:      skillNames,
			})
		}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"count":   len(bundles),
		"bundles": bundles,
	})
}

func (h *Handler) SkillsInstallHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req SkillInstallReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"error": "invalid json"})
		return
	}

	baseDir := getSkillsBaseDir()
	if baseDir == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"error": "skills base directory not found"})
		return
	}

	srcSkillDir := filepath.Join(baseDir, "skills", req.ID)
	if fi, err := os.Stat(srcSkillDir); err != nil || !fi.IsDir() {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"error": "skill not found"})
		return
	}

	var targetDir string
	switch strings.ToLower(req.Target) {
	case "gemini":
		targetDir = config.ExpandHome("~/.gemini/config/skills")
	case "codex":
		targetDir = config.ExpandHome("~/.codex/skills")
	case "claude":
		targetDir = config.ExpandHome("~/.claude/skills")
	case "opencode":
		targetDir = config.ExpandHome("~/.opencode/skills")
	default:
		if req.ProjectPath == "" {
			req.ProjectPath = h.Cfg.WorkspaceDir
		}
		targetDir = filepath.Join(config.ExpandHome(req.ProjectPath), ".agents/skills")
	}

	destDir := filepath.Join(targetDir, req.ID)
	os.MkdirAll(targetDir, 0755)

	// Copy recursively
	copyDir(srcSkillDir, destDir)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success":       true,
		"installedPath": destDir,
		"message":       "Skill installed successfully",
	})
}

func copyDir(src string, dst string) error {
	return filepath.Walk(src, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0755)
		}
		data, err := ioutil.ReadFile(path)
		if err != nil {
			return err
		}
		return ioutil.WriteFile(target, data, info.Mode())
	})
}

func (h *Handler) SkillsInstalledHandler(w http.ResponseWriter, r *http.Request) {
	target := r.URL.Query().Get("target")
	projectPath := r.URL.Query().Get("projectPath")

	var targetDir string
	switch strings.ToLower(target) {
	case "gemini":
		targetDir = config.ExpandHome("~/.gemini/config/skills")
	case "codex":
		targetDir = config.ExpandHome("~/.codex/skills")
	case "claude":
		targetDir = config.ExpandHome("~/.claude/skills")
	case "opencode":
		targetDir = config.ExpandHome("~/.opencode/skills")
	default:
		if projectPath == "" {
			projectPath = h.Cfg.WorkspaceDir
		}
		targetDir = filepath.Join(config.ExpandHome(projectPath), ".agents/skills")
	}

	entries, err := ioutil.ReadDir(targetDir)
	var skills []string
	if err == nil {
		for _, e := range entries {
			if e.IsDir() && fileExists(filepath.Join(targetDir, e.Name(), "SKILL.md")) {
				skills = append(skills, e.Name())
			}
		}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"target": target,
		"path":   targetDir,
		"count":  len(skills),
		"skills": skills,
	})
}

func fileExists(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && !fi.IsDir()
}
