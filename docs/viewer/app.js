(function () {
  "use strict";

  const DATA_URL = "../catalog.ndjson";
  const KNOWN_VIEWS = new Set(["dashboard", "semantics", "project", "experiments"]);
  const PROJECT_KINDS = new Set([
    "baseline",
    "work",
    "task",
    "risk",
    "evidence_request",
    "constraint",
    "readiness",
    "readiness_area",
    "milestone",
    "project",
    "progress",
    "gate"
  ]);
  const EXPERIMENT_KINDS = new Set(["run", "experiment"]);
  const LINK_FIELDS = new Set([
    "id",
    "ref",
    "refs",
    "guard",
    "guards",
    "tracking",
    "evidence",
    "related",
    "depends_on",
    "blocked_by",
    "cases"
  ]);
  const EVIDENCE_FIELDS = new Set(["evidence", "path", "report", "source"]);

  const state = {
    records: [],
    entries: [],
    byId: new Map(),
    backlinks: new Map(),
    basisSources: new Map(),
    axisBasisSources: new Map(),
    referenceResolvers: {},
    caseDocuments: new Map(),
    renderVersion: 0,
    filters: {
      search: "",
      kind: "",
      axis: "",
      classification: "",
      coverage: "",
      status: ""
    },
    loadedAt: null
  };

  const dom = {
    root: document.getElementById("view-root"),
    message: document.getElementById("message-panel"),
    summary: document.getElementById("catalog-summary"),
    reload: document.getElementById("reload-button"),
    clear: document.getElementById("clear-filters"),
    search: document.getElementById("search-filter"),
    kind: document.getElementById("kind-filter"),
    axis: document.getElementById("axis-filter"),
    classification: document.getElementById("classification-filter"),
    coverage: document.getElementById("coverage-filter"),
    status: document.getElementById("status-filter")
  };

  function element(tag, attributes, children) {
    const node = document.createElement(tag);
    if (attributes) {
      Object.entries(attributes).forEach(function (entry) {
        const name = entry[0];
        const value = entry[1];
        if (value === undefined || value === null) {
          return;
        }
        if (name === "className") {
          node.className = value;
        } else if (name === "text") {
          node.textContent = value;
        } else if (name === "dataset") {
          Object.entries(value).forEach(function (dataEntry) {
            node.dataset[dataEntry[0]] = dataEntry[1];
          });
        } else {
          node.setAttribute(name, value);
        }
      });
    }
    appendChildren(node, children);
    return node;
  }

  function appendChildren(parent, children) {
    if (children === undefined || children === null) {
      return;
    }
    const values = Array.isArray(children) ? children : [children];
    values.forEach(function (child) {
      if (child === undefined || child === null) {
        return;
      }
      parent.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
    });
  }

  function replaceContents(parent, children) {
    while (parent.firstChild) {
      parent.removeChild(parent.firstChild);
    }
    appendChildren(parent, children);
  }

  function normalizedKind(record) {
    return String(record.kind || "unknown").toLowerCase();
  }

  function scalarValues(value) {
    if (value === undefined || value === null || value === "") {
      return [];
    }
    if (Array.isArray(value)) {
      return value.flatMap(scalarValues);
    }
    if (typeof value === "object") {
      return Object.values(value).flatMap(scalarValues);
    }
    return [String(value)];
  }

  function valuesForField(record, field) {
    if (field === "status") {
      return scalarValues(record.status).concat(scalarValues(record.state));
    }
    return scalarValues(record[field]);
  }

  function compareText(left, right) {
    return String(left).localeCompare(String(right), undefined, {
      numeric: true,
      sensitivity: "base"
    });
  }

  function routeForRecord(id) {
    return "#/record/" + encodeURIComponent(id);
  }

  function searchRoute(value) {
    return "#/search/" + encodeURIComponent(value);
  }

  function routeForCase(id) {
    return "#/case/" + encodeURIComponent(id);
  }

  function parseRoute() {
    const hash = window.location.hash || "#/dashboard";
    if (hash.indexOf("#/record/") === 0) {
      return {
        type: "record",
        id: safeDecode(hash.slice("#/record/".length))
      };
    }
    if (hash.indexOf("#/case/") === 0) {
      const id = safeDecode(hash.slice("#/case/".length));
      return id ? { type: "case", id: id } : { type: "view", view: "dashboard", fallback: true };
    }
    if (hash.indexOf("#/search/") === 0) {
      return {
        type: "view",
        view: "dashboard",
        search: safeDecode(hash.slice("#/search/".length))
      };
    }
    const candidate = hash.replace(/^#\//, "").split("/")[0];
    return {
      type: "view",
      view: KNOWN_VIEWS.has(candidate) ? candidate : "dashboard"
    };
  }

  function safeDecode(value) {
    try {
      return decodeURIComponent(value);
    } catch (error) {
      return value;
    }
  }

  function setBusy(busy) {
    dom.root.setAttribute("aria-busy", busy ? "true" : "false");
    dom.reload.disabled = busy;
  }

  function showMessage(title, details) {
    const content = [element("strong", { text: title })];
    if (details) {
      content.push(element("p", { text: details }));
    }
    replaceContents(dom.message, content);
    dom.message.hidden = false;
  }

  function clearMessage() {
    dom.message.hidden = true;
    replaceContents(dom.message, null);
  }

  function loadCatalog() {
    state.renderVersion += 1;
    setBusy(true);
    clearMessage();
    state.caseDocuments = new Map();
    replaceContents(dom.root, element("p", { className: "loading", text: "正在解析 docs/catalog.ndjson…" }));

    return fetch(DATA_URL, { cache: "no-store" }).then(function (response) {
      if (!response.ok) {
        throw new Error("HTTP " + response.status + " " + response.statusText);
      }
      return response.text();
    }).then(function (body) {
      const parsed = parseNdjson(body);
      installRecords(parsed);
      populateFilters();
      state.loadedAt = new Date();
      dom.summary.textContent = state.records.length + " 条记录 · " + state.byId.size + " 个可链接 ID";
      render();
    }).catch(function (error) {
      state.records = [];
      state.entries = [];
      state.byId = new Map();
      state.backlinks = new Map();
      state.basisSources = new Map();
      state.axisBasisSources = new Map();
      state.referenceResolvers = {};
      state.caseDocuments = new Map();
      dom.summary.textContent = "catalog 载入失败";
      replaceContents(dom.root, null);
      showMessage(
        "无法载入结构化文档",
        String(error.message || error) + "。请从仓库根目录启动 HTTP 服务，不要直接使用 file:// 打开页面。"
      );
    }).then(function (value) {
      setBusy(false);
      return value;
    }, function (error) {
      setBusy(false);
      throw error;
    });
  }

  function parseNdjson(body) {
    const records = [];
    body.split(/\r?\n/).forEach(function (line, index) {
      const trimmed = line.trim();
      if (!trimmed) {
        return;
      }
      let record;
      try {
        record = JSON.parse(trimmed);
      } catch (error) {
        throw new Error("第 " + (index + 1) + " 行不是合法 JSON：" + error.message);
      }
      if (!record || Array.isArray(record) || typeof record !== "object") {
        throw new Error("第 " + (index + 1) + " 行必须是 JSON object");
      }
      records.push({ record: record, line: index + 1 });
    });
    return records;
  }

  function installRecords(parsed) {
    const byId = new Map();
    parsed.forEach(function (entry) {
      if (entry.record.id === undefined || entry.record.id === null || entry.record.id === "") {
        return;
      }
      const id = String(entry.record.id);
      if (byId.has(id)) {
        throw new Error("重复 ID：" + id + "（第 " + entry.line + " 行）");
      }
      byId.set(id, entry.record);
    });
    state.entries = parsed;
    state.records = parsed.map(function (entry) { return entry.record; });
    state.byId = byId;
    state.backlinks = buildBacklinks(state.records, byId);
    installMetadata(state.records);
  }

  function installMetadata(records) {
    const basisSources = new Map();
    const axisBasisSources = new Map();
    let referenceResolvers = {};
    records.filter(function (record) {
      return normalizedKind(record) === "meta";
    }).forEach(function (record) {
      if (record.basis_sources && typeof record.basis_sources === "object" && !Array.isArray(record.basis_sources)) {
        Object.entries(record.basis_sources).forEach(function (entry) {
          basisSources.set(entry[0], scalarValues(entry[1]));
        });
      }
      if (record.axis_basis_sources && typeof record.axis_basis_sources === "object" &&
          !Array.isArray(record.axis_basis_sources)) {
        Object.entries(record.axis_basis_sources).forEach(function (axisEntry) {
          if (!axisEntry[1] || typeof axisEntry[1] !== "object" || Array.isArray(axisEntry[1])) {
            return;
          }
          const sourcesByBasis = axisBasisSources.get(axisEntry[0]) || new Map();
          Object.entries(axisEntry[1]).forEach(function (basisEntry) {
            sourcesByBasis.set(basisEntry[0], scalarValues(basisEntry[1]));
          });
          axisBasisSources.set(axisEntry[0], sourcesByBasis);
        });
      }
      if (record.reference_resolvers && typeof record.reference_resolvers === "object" && !Array.isArray(record.reference_resolvers)) {
        referenceResolvers = record.reference_resolvers;
      }
    });
    state.basisSources = basisSources;
    state.axisBasisSources = axisBasisSources;
    state.referenceResolvers = referenceResolvers;
  }

  function buildBacklinks(records, byId) {
    const backlinks = new Map();
    byId.forEach(function (_record, id) {
      backlinks.set(id, []);
    });

    records.forEach(function (source) {
      if (source.id === undefined || source.id === null) {
        return;
      }
      const sourceId = String(source.id);
      const seen = new Set();
      Object.entries(source).forEach(function (entry) {
        if (entry[0] === "id") {
          return;
        }
        findReferencedIds(entry[1], entry[0], byId).forEach(function (reference) {
          if (reference.id === sourceId) {
            return;
          }
          const key = reference.id + "\u0000" + reference.path;
          if (seen.has(key)) {
            return;
          }
          seen.add(key);
          backlinks.get(reference.id).push({
            sourceId: sourceId,
            path: reference.path
          });
        });
      });
    });

    backlinks.forEach(function (items) {
      items.sort(function (left, right) {
        return compareText(left.sourceId, right.sourceId) || compareText(left.path, right.path);
      });
    });
    return backlinks;
  }

  function findReferencedIds(value, path, byId) {
    const found = [];
    if (Array.isArray(value)) {
      value.forEach(function (item, index) {
        found.push.apply(found, findReferencedIds(item, path + "[" + index + "]", byId));
      });
      return found;
    }
    if (value && typeof value === "object") {
      Object.entries(value).forEach(function (entry) {
        found.push.apply(found, findReferencedIds(entry[1], path + "." + entry[0], byId));
      });
      return found;
    }
    if (typeof value !== "string") {
      return found;
    }
    if (value.indexOf("record:") === 0 && byId.has(value.slice("record:".length))) {
      found.push({ id: value.slice("record:".length), path: path });
      return found;
    }
    if (byId.has(value)) {
      found.push({ id: value, path: path });
      return found;
    }
    if (!isLinkField(path)) {
      return found;
    }
    byId.forEach(function (_target, id) {
      if (containsReferenceToken(value, id)) {
        found.push({ id: id, path: path });
      }
    });
    return found;
  }

  function containsReferenceToken(text, id) {
    let from = 0;
    while (from <= text.length) {
      const index = text.indexOf(id, from);
      if (index < 0) {
        return false;
      }
      const before = index === 0 ? "" : text.charAt(index - 1);
      const afterIndex = index + id.length;
      const after = afterIndex >= text.length ? "" : text.charAt(afterIndex);
      if (!/[A-Za-z0-9_.-]/.test(before) && !/[A-Za-z0-9_.-]/.test(after)) {
        return true;
      }
      from = index + id.length;
    }
    return false;
  }

  function isLinkField(path) {
    return path.split(/[.\[]/).some(function (part) {
      const normalized = part.toLowerCase();
      return LINK_FIELDS.has(normalized) ||
        normalized.endsWith("_refs") ||
        normalized.endsWith("_evidence") ||
        normalized.endsWith("_guards") ||
        normalized.endsWith("_tracking");
    });
  }

  function populateFilters() {
    setSelectOptions(dom.kind, distinctValues("kind"));
    setSelectOptions(dom.axis, distinctValues("axis"));
    setSelectOptions(dom.classification, distinctValues("classification"));
    setSelectOptions(dom.coverage, distinctValues("coverage"));
    setSelectOptions(dom.status, distinctStatusValues());
  }

  function distinctValues(field) {
    const values = new Set();
    state.records.forEach(function (record) {
      valuesForField(record, field).forEach(function (value) { values.add(value); });
    });
    return Array.from(values).sort(compareText);
  }

  function distinctStatusValues() {
    const values = new Set();
    state.records.forEach(function (record) {
      valuesForField(record, "status").forEach(function (value) { values.add(value); });
    });
    return Array.from(values).sort(compareText);
  }

  function setSelectOptions(select, values) {
    const selected = select.value;
    const options = [element("option", { value: "", text: "全部" })];
    values.forEach(function (value) {
      options.push(element("option", { value: value, text: value }));
    });
    replaceContents(select, options);
    select.value = values.indexOf(selected) >= 0 ? selected : "";
  }

  function syncFiltersFromDom() {
    state.filters.search = dom.search.value.trim();
    state.filters.kind = dom.kind.value;
    state.filters.axis = dom.axis.value;
    state.filters.classification = dom.classification.value;
    state.filters.coverage = dom.coverage.value;
    state.filters.status = dom.status.value;
  }

  function matchesFilters(record) {
    const query = state.filters.search.toLocaleLowerCase();
    if (query && JSON.stringify(record).toLocaleLowerCase().indexOf(query) < 0) {
      return false;
    }
    return ["kind", "axis", "classification", "coverage", "status"].every(function (field) {
      const selected = state.filters[field];
      return !selected || valuesForField(record, field).indexOf(selected) >= 0;
    });
  }

  function filteredRecords() {
    return state.records.filter(matchesFilters);
  }

  function render() {
    if (!state.loadedAt) {
      return;
    }
    clearMessage();
    const route = parseRoute();
    const version = state.renderVersion + 1;
    state.renderVersion = version;
    if (route.fallback) {
      window.history.replaceState(null, "", "#/dashboard");
    }
    if (route.search !== undefined && dom.search.value !== route.search) {
      dom.search.value = route.search;
      syncFiltersFromDom();
    }
    updateNavigation(route);
    if (route.type !== "case") {
      setBusy(false);
    }
    if (route.type === "record") {
      renderRecord(route.id);
      return;
    }
    if (route.type === "case") {
      renderCase(route.id, version);
      return;
    }
    const records = filteredRecords();
    if (route.view === "semantics") {
      renderSemantics(records);
    } else if (route.view === "project") {
      renderProject(records);
    } else if (route.view === "experiments") {
      renderExperiments(records);
    } else {
      renderDashboard(records);
    }
  }

  function updateNavigation(route) {
    const activeView = route.type === "view" ? route.view :
      (route.type === "record" ? viewForRecord(state.byId.get(route.id)) : "dashboard");
    document.querySelectorAll(".primary-nav a").forEach(function (link) {
      if (link.dataset.view === activeView) {
        link.setAttribute("aria-current", "page");
      } else {
        link.removeAttribute("aria-current");
      }
    });
  }

  function viewForRecord(record) {
    if (!record) {
      return "dashboard";
    }
    const kind = normalizedKind(record);
    if (kind === "semantic") {
      return "semantics";
    }
    if (PROJECT_KINDS.has(kind)) {
      return "project";
    }
    if (EXPERIMENT_KINDS.has(kind)) {
      return "experiments";
    }
    return "dashboard";
  }

  function pageHeader(kicker, title, description, count, total) {
    const heading = element("div", { className: "view-heading" }, [
      element("div", null, [
        element("p", { className: "eyebrow", text: kicker }),
        element("h2", { text: title }),
        description ? element("p", { className: "view-description", text: description }) : null
      ]),
      element("p", {
        className: "result-count",
        text: count === total ? count + " 条记录" : count + " / " + total + " 条记录"
      })
    ]);
    return heading;
  }

  function renderDashboard(records) {
    const sections = [pageHeader(
      "Overview",
      "Dashboard",
      "所有数字由当前载入的 catalog 和筛选条件即时计算。",
      records.length,
      state.records.length
    )];

    sections.push(metricGrid([
      { label: "记录", value: records.length },
      { label: "Kinds", value: new Set(records.map(normalizedKind)).size },
      { label: "Semantics", value: records.filter(function (record) { return normalizedKind(record) === "semantic"; }).length },
      { label: "Experiments", value: records.filter(function (record) { return EXPERIMENT_KINDS.has(normalizedKind(record)); }).length }
    ]));

    sections.push(summaryPanels(records));
    sections.push(element("section", { className: "content-section" }, [
      element("div", { className: "section-heading" }, [
        element("h3", { text: "匹配记录" }),
        element("p", { text: records.length > 100 ? "显示前 100 条；继续筛选可缩小范围。" : "点击 ID 查看全部字段与反向引用。" })
      ]),
      recordList(records.slice(0, 100))
    ]));
    replaceContents(dom.root, sections);
  }

  function metricGrid(metrics) {
    return element("section", { className: "metric-grid", "aria-label": "Catalog summary" }, metrics.map(function (metric) {
      return element("article", { className: "metric-card" }, [
        element("span", { className: "metric-value", text: String(metric.value) }),
        element("span", { className: "metric-label", text: metric.label })
      ]);
    }));
  }

  function summaryPanels(records) {
    const definitions = [
      { field: "kind", title: "Kinds" },
      { field: "classification", title: "Classification" },
      { field: "coverage", title: "Coverage" },
      { field: "status", title: "Status / state" }
    ];
    return element("section", { className: "summary-grid", "aria-label": "Derived breakdowns" }, definitions.map(function (definition) {
      const counts = countValues(records, definition.field);
      const list = element("ul", { className: "count-list" });
      Array.from(counts.entries()).sort(function (left, right) {
        return right[1] - left[1] || compareText(left[0], right[0]);
      }).forEach(function (entry) {
        list.appendChild(element("li", null, [
          createBadge(entry[0]),
          element("span", { className: "count-value", text: String(entry[1]) })
        ]));
      });
      if (!counts.size) {
        list.appendChild(element("li", { className: "empty-inline", text: "无数据" }));
      }
      return element("article", { className: "summary-card" }, [
        element("h3", { text: definition.title }),
        list
      ]);
    }));
  }

  function countValues(records, field) {
    const counts = new Map();
    records.forEach(function (record) {
      valuesForField(record, field).forEach(function (value) {
        counts.set(value, (counts.get(value) || 0) + 1);
      });
    });
    return counts;
  }

  function renderSemantics(records) {
    const semantics = records.filter(function (record) { return normalizedKind(record) === "semantic"; });
    const section = element("section", { className: "content-section" }, [
      pageHeader(
        "Decision-level behavior",
        "Semantics",
        "按 Basis、Classification、Coverage 与精确 guard 浏览语义决策。",
        semantics.length,
        state.records.filter(function (record) { return normalizedKind(record) === "semantic"; }).length
      ),
      semanticAxisSummary(semantics),
      semanticTable(semantics)
    ]);
    replaceContents(dom.root, section);
  }

  function semanticAxisSummary(records) {
    const counts = countValues(records, "axis");
    const list = element("ul", { className: "count-list semantic-axis-counts" });
    Array.from(counts.entries()).sort(function (left, right) {
      return compareText(left[0], right[0]);
    }).forEach(function (entry) {
      list.appendChild(element("li", null, [
        createBadge(entry[0]),
        element("span", { className: "count-value", text: String(entry[1]) })
      ]));
    });
    if (!counts.size) {
      list.appendChild(element("li", { className: "empty-inline", text: "当前筛选没有语义轴。" }));
    }
    return element("section", { className: "semantic-axis-summary", "aria-label": "Semantic records by axis" }, [
      element("div", { className: "section-heading" }, [
        element("h3", { text: "Axis counts" }),
        element("p", { text: counts.size + " 个轴" })
      ]),
      list
    ]);
  }

  function semanticTable(records) {
    if (!records.length) {
      return emptyState("没有语义记录符合当前筛选条件。");
    }
    const table = element("table", { className: "record-table" });
    table.appendChild(element("thead", null, element("tr", null, [
      element("th", { scope: "col", text: "ID" }),
      element("th", { scope: "col", text: "Axis / condition" }),
      element("th", { scope: "col", text: "Expected" }),
      element("th", { scope: "col", text: "Basis" }),
      element("th", { scope: "col", text: "Classification" }),
      element("th", { scope: "col", text: "Coverage" })
    ])));
    const body = element("tbody");
    records.slice().sort(function (left, right) {
      return compareText(left.axis || "", right.axis || "") || compareText(left.id || "", right.id || "");
    }).forEach(function (record) {
      body.appendChild(element("tr", null, [
        element("td", { dataset: { label: "ID" } }, recordIdLink(record)),
        element("td", { dataset: { label: "Axis / condition" } }, [
          record.axis ? createBadge(String(record.axis)) : null,
          element("p", { text: firstText(record, ["condition", "decision", "title", "summary"]) })
        ]),
        element("td", { dataset: { label: "Expected" } }, valuePreview(record.expected)),
        element("td", { dataset: { label: "Basis" } }, compactBasis(record.basis, record.axis)),
        element("td", { dataset: { label: "Classification" } }, badgesFor(record.classification)),
        element("td", { dataset: { label: "Coverage" } }, badgesFor(record.coverage))
      ]));
    });
    table.appendChild(body);
    return element("div", { className: "table-scroll" }, table);
  }

  function renderProject(records) {
    const projectRecords = records.filter(function (record) { return PROJECT_KINDS.has(normalizedKind(record)); });
    const allProject = state.records.filter(function (record) { return PROJECT_KINDS.has(normalizedKind(record)); });
    const sections = [pageHeader(
      "Delivery state",
      "Project / Readiness",
      "工作项、风险、约束、证据请求与 readiness gate 的统一投影。",
      projectRecords.length,
      allProject.length
    )];
    if (!projectRecords.length) {
      sections.push(emptyState("没有 Project / Readiness 记录符合当前筛选条件。"));
    } else {
      groupedByKind(projectRecords).forEach(function (group) {
        sections.push(element("section", { className: "content-section" }, [
          element("div", { className: "section-heading" }, [
            element("h3", { text: group.kind }),
            element("p", { text: group.records.length + " 条" })
          ]),
          recordList(group.records)
        ]));
      });
    }
    replaceContents(dom.root, sections);
  }

  function groupedByKind(records) {
    const groups = new Map();
    records.forEach(function (record) {
      const kind = normalizedKind(record);
      if (!groups.has(kind)) {
        groups.set(kind, []);
      }
      groups.get(kind).push(record);
    });
    return Array.from(groups.entries()).sort(function (left, right) {
      return compareText(left[0], right[0]);
    }).map(function (entry) {
      return {
        kind: entry[0],
        records: entry[1].sort(function (left, right) { return compareText(left.id || "", right.id || ""); })
      };
    });
  }

  function renderExperiments(records) {
    const experiments = records.filter(function (record) { return EXPERIMENT_KINDS.has(normalizedKind(record)); });
    experiments.sort(function (left, right) {
      return compareText(right.date || "", left.date || "") || compareText(right.id || "", left.id || "");
    });
    const allExperiments = state.records.filter(function (record) { return EXPERIMENT_KINDS.has(normalizedKind(record)); });
    const sections = [pageHeader(
      "Execution evidence",
      "Experiments",
      "点击实验 ID 查看命令、引用和 evidence；时间线按日期倒序排列。",
      experiments.length,
      allExperiments.length
    )];
    if (!experiments.length) {
      sections.push(emptyState("没有实验记录符合当前筛选条件。"));
    } else {
      const timeline = element("ol", { className: "timeline" });
      experiments.forEach(function (record) {
        const heading = element("h3", null, recordIdLink(record));
        const meta = element("div", { className: "record-meta" }, [
          record.date ? createBadge(String(record.date)) : null,
          badgesFor(record.status || record.state),
          record.commit ? createBadge("commit " + String(record.commit)) :
            (record.worktree_base ? createBadge(
              "worktree " + String(record.worktree_base) + (record.dirty ? " + dirty" : "")
            ) : (record.revision_unknown ? createBadge("revision unknown") : null))
        ]);
        const evidence = relationPreview(record.evidence, "evidence");
        timeline.appendChild(element("li", null, element("article", { className: "timeline-card" }, [
          heading,
          meta,
          element("p", { className: "record-summary", text: firstText(record, ["summary", "title", "description"]) }),
          evidence ? element("div", { className: "inline-relations" }, [
            element("strong", { text: "Evidence：" }),
            evidence
          ]) : null
        ])));
      });
      sections.push(timeline);
    }
    replaceContents(dom.root, sections);
  }

  function renderRecord(id) {
    const record = state.byId.get(id);
    if (!record) {
      replaceContents(dom.root, [
        pageHeader("Record", "未找到记录", "catalog 中不存在 ID “" + id + "”。", 0, state.records.length),
        element("p", null, element("a", { href: searchRoute(id), text: "在 catalog 中搜索这个值" }))
      ]);
      return;
    }

    const backView = viewForRecord(record);
    const fields = element("dl", { className: "record-fields" });
    Object.entries(record).forEach(function (entry) {
      fields.appendChild(element("div", { className: "field-row" }, [
        element("dt", { text: entry[0] }),
        element("dd", null, renderValue(entry[1], entry[0], record.axis))
      ]));
    });

    const backlinks = state.backlinks.get(String(record.id)) || [];
    const backlinkSection = element("section", { className: "backlinks content-section" }, [
      element("div", { className: "section-heading" }, [
        element("h3", { text: "Backlinks" }),
        element("p", { text: backlinks.length + " 个字段引用此记录" })
      ]),
      backlinkList(backlinks)
    ]);

    replaceContents(dom.root, [
      element("nav", { className: "breadcrumbs", "aria-label": "Breadcrumb" }, [
        element("a", { href: "#/" + backView, text: viewTitle(backView) }),
        element("span", { "aria-hidden": "true", text: "/" }),
        element("span", { text: String(record.id) })
      ]),
      element("article", { className: "record-detail" }, [
        element("header", { className: "detail-header" }, [
          element("div", null, [
            element("p", { className: "eyebrow", text: normalizedKind(record) }),
            element("h2", null, recordIdLink(record))
          ]),
          element("a", { className: "secondary-button", href: searchRoute(String(record.id)), text: "查找所有匹配" })
        ]),
        fields
      ]),
      backlinkSection
    ]);
  }

  function renderCase(caseId, version) {
    const source = caseSource(caseId);
    const references = catalogCaseReferences(caseId);
    replaceContents(dom.root, caseDetailContents(caseId, source, references, {
      state: "loading",
      message: source ? "正在读取测试定义…" : "Catalog meta 没有为此用例配置可读取的索引。"
    }));

    if (!source) {
      setBusy(false);
      return Promise.resolve();
    }

    setBusy(true);
    return fetchCaseDocument(source.target).then(function (markdown) {
      if (version !== state.renderVersion) {
        return;
      }
      const section = extractCaseSection(markdown, caseId);
      if (!section) {
        replaceContents(dom.root, caseDetailContents(caseId, source, references, {
          state: "missing",
          message: "源文件已载入，但没有找到“## " + caseId + ":”小节。"
        }));
        return;
      }
      replaceContents(dom.root, caseDetailContents(caseId, source, references, {
        state: "ready",
        section: section
      }));
    }).catch(function (error) {
      if (version !== state.renderVersion) {
        return;
      }
      replaceContents(dom.root, caseDetailContents(caseId, source, references, {
        state: "error",
        message: "无法读取测试定义：" + String(error.message || error)
      }));
    }).then(function (value) {
      if (version === state.renderVersion) {
        setBusy(false);
      }
      return value;
    }, function (error) {
      if (version === state.renderVersion) {
        setBusy(false);
      }
      throw error;
    });
  }

  function caseDetailContents(caseId, source, references, result) {
    const sourceLink = source ? element("a", {
      className: "secondary-button",
      href: source.target,
      target: "_blank",
      rel: "noopener",
      text: "打开源文件"
    }) : null;
    const body = result.state === "ready" ?
      element("pre", { className: "case-source", text: result.section }) :
      element("div", { className: result.state === "loading" ? "loading" : "case-error" }, [
        element("strong", { text: result.state === "missing" ? "未找到用例小节" :
          (result.state === "error" ? "载入失败" : "载入中") }),
        element("p", { text: result.message })
      ]);

    return [
      element("nav", { className: "breadcrumbs", "aria-label": "Breadcrumb" }, [
        element("a", { href: "#/dashboard", text: "Dashboard" }),
        element("span", { "aria-hidden": "true", text: "/" }),
        element("span", { text: "Test case" }),
        element("span", { "aria-hidden": "true", text: "/" }),
        element("span", { text: caseId })
      ]),
      element("article", { className: "record-detail case-detail" }, [
        element("header", { className: "detail-header" }, [
          element("div", null, [
            element("p", { className: "eyebrow", text: "Test case definition" }),
            element("h2", { text: caseId }),
            source ? element("p", { className: "case-origin", text: source.reference }) : null
          ]),
          sourceLink
        ]),
        body
      ]),
      element("section", { className: "backlinks content-section" }, [
        element("div", { className: "section-heading" }, [
          element("h3", { text: "Catalog references" }),
          element("p", { text: references.length + " 条记录引用此用例" })
        ]),
        caseReferenceList(references)
      ])
    ];
  }

  function caseSource(caseId) {
    const resolver = state.referenceResolvers.case;
    const indexes = resolver && Array.isArray(resolver.indexes) ? resolver.indexes : [];
    const ecosystem = /^(?:SP|ECO)-/.test(caseId);
    const selected = indexes.find(function (reference) {
      const value = String(reference);
      return ecosystem ? value.indexOf("TEST-CASES-ECO.md") >= 0 : value.indexOf("TEST-PLAN.md") >= 0;
    });
    if (selected === undefined) {
      return null;
    }
    const parsed = parseStructuredReference(String(selected));
    if (parsed.type !== "path") {
      return null;
    }
    const target = repoRelativeTarget(parsed.value, "case", true);
    return target ? { reference: String(selected), target: target } : null;
  }

  function fetchCaseDocument(target) {
    if (state.caseDocuments.has(target)) {
      return state.caseDocuments.get(target);
    }
    const pending = fetch(target, { cache: "no-store" }).then(function (response) {
      if (!response.ok) {
        throw new Error("HTTP " + response.status + " " + response.statusText);
      }
      return response.text();
    });
    state.caseDocuments.set(target, pending);
    return pending.catch(function (error) {
      if (state.caseDocuments.get(target) === pending) {
        state.caseDocuments.delete(target);
      }
      throw error;
    });
  }

  function extractCaseSection(markdown, caseId) {
    const marker = "## " + caseId + ":";
    const lines = markdown.split(/\r?\n/);
    const start = lines.findIndex(function (line) { return line.indexOf(marker) === 0; });
    if (start < 0) {
      return null;
    }
    let end = start + 1;
    while (end < lines.length && !/^##\s+/.test(lines[end])) {
      end += 1;
    }
    return lines.slice(start, end).join("\n").trim();
  }

  function catalogCaseReferences(caseId) {
    const resolver = state.referenceResolvers.case;
    const prefix = resolver && typeof resolver.prefix === "string" ? resolver.prefix : "case:";
    const needle = prefix + caseId;
    const references = [];
    state.records.forEach(function (record) {
      if (record.id === undefined || record.id === null) {
        return;
      }
      const paths = [];
      Object.entries(record).forEach(function (entry) {
        findCaseReferencePaths(entry[1], entry[0], needle, caseId).forEach(function (path) {
          if (paths.indexOf(path) < 0) {
            paths.push(path);
          }
        });
      });
      if (paths.length) {
        references.push({ id: String(record.id), paths: paths });
      }
    });
    return references.sort(function (left, right) { return compareText(left.id, right.id); });
  }

  function findCaseReferencePaths(value, path, needle, caseId) {
    const found = [];
    if (Array.isArray(value)) {
      value.forEach(function (item, index) {
        found.push.apply(found, findCaseReferencePaths(item, path + "[" + index + "]", needle, caseId));
      });
      return found;
    }
    if (value && typeof value === "object") {
      Object.entries(value).forEach(function (entry) {
        found.push.apply(found, findCaseReferencePaths(entry[1], path + "." + entry[0], needle, caseId));
      });
      return found;
    }
    if (typeof value === "string" && (value === needle || (isLinkField(path) && value === caseId))) {
      found.push(path);
    }
    return found;
  }

  function caseReferenceList(references) {
    if (!references.length) {
      return emptyState("当前 catalog 中没有记录引用此用例。");
    }
    const list = element("ul", { className: "backlink-list" });
    references.forEach(function (reference) {
      list.appendChild(element("li", null, [
        internalRecordLink(reference.id),
        element("span", { text: " via " + reference.paths.join(", ") })
      ]));
    });
    return list;
  }

  function viewTitle(view) {
    if (view === "semantics") {
      return "Semantics";
    }
    if (view === "project") {
      return "Project / Readiness";
    }
    if (view === "experiments") {
      return "Experiments";
    }
    return "Dashboard";
  }

  function backlinkList(backlinks) {
    if (!backlinks.length) {
      return emptyState("当前 catalog 中没有其他记录引用此 ID。");
    }
    const list = element("ul", { className: "backlink-list" });
    backlinks.forEach(function (backlink) {
      list.appendChild(element("li", null, [
        internalRecordLink(backlink.sourceId),
        element("span", { text: " via " + backlink.path })
      ]));
    });
    return list;
  }

  function recordList(records) {
    if (!records.length) {
      return emptyState("没有记录符合当前筛选条件。");
    }
    const list = element("div", { className: "record-list" });
    records.forEach(function (record) {
      const heading = element("h4", null, recordIdLink(record));
      const metadata = [createBadge(normalizedKind(record))];
      ["axis", "classification"].forEach(function (field) {
        valuesForField(record, field).forEach(function (value) { metadata.push(createBadge(value)); });
      });
      valuesForField(record, "status").forEach(function (value) { metadata.push(createBadge(value)); });
      valuesForField(record, "coverage").forEach(function (value) { metadata.push(createBadge(value)); });
      list.appendChild(element("article", { className: "record-card" }, [
        heading,
        element("div", { className: "record-meta" }, metadata),
        element("p", { className: "record-summary", text: firstText(record, ["title", "summary", "condition", "decision", "description"]) })
      ]));
    });
    return list;
  }

  function firstText(record, fields) {
    for (let index = 0; index < fields.length; index += 1) {
      const value = record[fields[index]];
      if (value !== undefined && value !== null && value !== "") {
        return scalarValues(value).join(" · ");
      }
    }
    return "—";
  }

  function recordIdLink(record) {
    if (record.id === undefined || record.id === null || record.id === "") {
      return element("span", { className: "missing-id", text: "（无 ID）" });
    }
    return internalRecordLink(String(record.id));
  }

  function internalRecordLink(id) {
    return element("a", { className: "record-link", href: routeForRecord(id), text: id });
  }

  function badgesFor(value) {
    const values = scalarValues(value);
    if (!values.length) {
      return element("span", { className: "empty-value", text: "—" });
    }
    return element("span", { className: "badge-list" }, values.map(createBadge));
  }

  function createBadge(value) {
    const text = String(value);
    const tone = badgeTone(text);
    return element("span", { className: "badge badge-" + tone }, [
      element("span", { className: "badge-symbol", "aria-hidden": "true", text: badgeSymbol(tone) }),
      element("span", { text: text })
    ]);
  }

  function badgeTone(value) {
    const normalized = value.toUpperCase();
    if (["PASS", "MATCH", "DONE", "COMPLETE", "COMPLETED", "READY", "SUPPORTED"].indexOf(normalized) >= 0) {
      return "positive";
    }
    if (["FAIL", "FAILED", "DIFFERENT", "BLOCKED", "ERROR", "UNSUPPORTED"].indexOf(normalized) >= 0) {
      return "negative";
    }
    if (["PENDING", "PARTIAL", "IN_PROGRESS", "RUNNING", "SPIKE", "WARN", "WARNING"].indexOf(normalized) >= 0) {
      return "warning";
    }
    if (["UNKNOWN", "NONE", "NOT_RUN", "N/A"].indexOf(normalized) >= 0) {
      return "unknown";
    }
    return "neutral";
  }

  function badgeSymbol(tone) {
    if (tone === "positive") {
      return "✓";
    }
    if (tone === "negative") {
      return "×";
    }
    if (tone === "warning") {
      return "!";
    }
    if (tone === "unknown") {
      return "?";
    }
    return "•";
  }

  function valuePreview(value) {
    const values = scalarValues(value);
    return element("span", { text: values.length ? values.join(" · ") : "—" });
  }

  function relationPreview(value, field) {
    const values = scalarValues(value);
    if (!values.length) {
      return null;
    }
    return element("span", { className: "relation-list" }, values.map(function (item) {
      return smartLink(item, field);
    }));
  }

  function renderValue(value, fieldPath, axis) {
    if (value === null || value === undefined) {
      return element("span", { className: "empty-value", text: "null" });
    }
    if (Array.isArray(value)) {
      if (!value.length) {
        return element("span", { className: "empty-value", text: "[]" });
      }
      const list = element("ul", { className: "value-list" });
      value.forEach(function (item, index) {
        list.appendChild(element("li", null, renderValue(item, fieldPath + "[" + index + "]", axis)));
      });
      return list;
    }
    if (typeof value === "object") {
      const list = element("dl", { className: "nested-fields" });
      Object.entries(value).forEach(function (entry) {
        list.appendChild(element("div", null, [
          element("dt", { text: entry[0] }),
          element("dd", null, renderValue(entry[1], fieldPath + "." + entry[0], axis))
        ]));
      });
      return list;
    }
    if (isBasisField(fieldPath) && hasBasisSources(String(value), axis)) {
      return basisReference(String(value), axis);
    }
    if (state.byId.has(String(value)) || isLinkField(fieldPath) || isStructuredReference(String(value))) {
      return smartLink(String(value), fieldPath);
    }
    if (typeof value === "boolean") {
      return element("code", { text: value ? "true" : "false" });
    }
    return element("span", { text: String(value) });
  }

  function smartLink(value, fieldPath) {
    const reference = parseStructuredReference(value);
    if (reference.type === "record") {
      if (state.byId.has(reference.value)) {
        return element("a", {
          className: "record-link",
          href: routeForRecord(reference.value),
          text: value
        });
      }
      return unresolvedReferenceLink(value, reference.value);
    }
    if (state.byId.has(value)) {
      return internalRecordLink(value);
    }
    if (reference.type === "case") {
      return caseReference(value, reference.value);
    }
    if (reference.type === "java") {
      return javaReference(value, reference.value);
    }
    const fileTarget = repoRelativeTarget(reference.type === "path" ? reference.value : value, fieldPath, reference.type === "path");
    if (fileTarget) {
      return element("a", {
        className: "evidence-link",
        href: fileTarget,
        target: "_blank",
        rel: "noopener",
        text: value
      });
    }
    const externalTarget = reference.type === "url" ? reference.value : value;
    if (/^https?:\/\//i.test(externalTarget)) {
      return element("a", {
        className: "external-link",
        href: externalTarget,
        target: "_blank",
        rel: "noopener noreferrer",
        text: value
      });
    }
    return unresolvedReferenceLink(value, reference.value);
  }

  function isBasisField(fieldPath) {
    return fieldPath.split(/[.\[]/).some(function (part) { return part.toLowerCase() === "basis"; });
  }

  function sourcesForBasis(value, axis) {
    const axisSources = axis === undefined || axis === null ? null : state.axisBasisSources.get(String(axis));
    if (axisSources && axisSources.has(value)) {
      return axisSources.get(value);
    }
    return state.basisSources.get(value) || [];
  }

  function hasBasisSources(value, axis) {
    const axisSources = axis === undefined || axis === null ? null : state.axisBasisSources.get(String(axis));
    return Boolean(axisSources && axisSources.has(value)) || state.basisSources.has(value);
  }

  function basisReference(value, axis) {
    const sources = sourcesForBasis(value, axis);
    const links = [createBadge(value)];
    sources.forEach(function (source, index) {
      const wrapper = smartLink(source, "basis_sources");
      wrapper.setAttribute("aria-label", value + " 来源 " + (index + 1) + "：" + source);
      wrapper.textContent = "来源 " + (index + 1);
      links.push(wrapper);
    });
    return element("span", { className: "basis-reference" }, links);
  }

  function compactBasis(value, axis) {
    const values = scalarValues(value);
    if (!values.length) {
      return element("span", { className: "empty-value", text: "—" });
    }
    return element("span", { className: "basis-list" }, values.map(function (item) {
      return hasBasisSources(item, axis) ? basisReference(item, axis) : createBadge(item);
    }));
  }

  function caseReference(label, caseId) {
    return element("span", { className: "reference-actions" }, [
      element("a", {
        className: "record-link",
        href: routeForCase(caseId),
        text: label
      }),
      element("a", {
        className: "reference-search",
        href: searchRoute(caseId),
        title: "在 catalog 中搜索此用例",
        "aria-label": "在 catalog 中搜索 " + caseId,
        text: "搜索"
      })
    ]);
  }

  function javaReference(label, symbol) {
    const resolver = state.referenceResolvers.java;
    const separator = resolver && typeof resolver.symbol_separator === "string" ? resolver.symbol_separator : "#";
    const separatorIndex = symbol.indexOf(separator);
    const className = separatorIndex >= 0 ? symbol.slice(0, separatorIndex) : symbol;
    const symbols = resolver && resolver.symbols && typeof resolver.symbols === "object" ? resolver.symbols : {};
    const classes = resolver && resolver.classes && typeof resolver.classes === "object" ? resolver.classes : {};
    const configured = Object.prototype.hasOwnProperty.call(symbols, symbol) ? symbols[symbol] : classes[className];
    let target = null;
    if (typeof configured === "string") {
      const parsed = parseStructuredReference(configured);
      if (parsed.type === "path") {
        target = repoRelativeTarget(parsed.value, "java", true);
      } else if (parsed.type === "url" && /^https?:\/\//i.test(parsed.value)) {
        target = parsed.value;
      }
    }
    return directReferenceWithSearch(label, symbol, target, "打开已校验的测试来源");
  }

  function directReferenceWithSearch(label, searchValue, directTarget, directTitle) {
    if (!directTarget) {
      return unresolvedReferenceLink(label, searchValue);
    }
    return element("span", { className: "reference-actions" }, [
      element("a", {
        className: "evidence-link",
        href: directTarget,
        target: "_blank",
        rel: /^https?:\/\//i.test(directTarget) ? "noopener noreferrer" : "noopener",
        title: directTitle,
        text: label
      }),
      element("a", {
        className: "reference-search",
        href: searchRoute(searchValue),
        title: "在 catalog 中搜索此引用",
        "aria-label": "在 catalog 中搜索 " + searchValue,
        text: "搜索"
      })
    ]);
  }

  function unresolvedReferenceLink(label, searchValue) {
    return element("a", {
      className: "search-link",
      href: searchRoute(searchValue),
      title: "搜索未解析的引用",
      text: label
    });
  }

  function isStructuredReference(value) {
    return Object.values(state.referenceResolvers).some(function (resolver) {
      return resolver && typeof resolver.prefix === "string" && value.indexOf(resolver.prefix) === 0;
    }) || /^(?:record|path|case|java|url):/.test(value);
  }

  function parseStructuredReference(value) {
    const configured = Object.entries(state.referenceResolvers).find(function (entry) {
      return entry[1] && typeof entry[1].prefix === "string" && value.indexOf(entry[1].prefix) === 0;
    });
    if (configured) {
      return { type: configured[0], value: value.slice(configured[1].prefix.length) };
    }
    const match = value.match(/^(record|path|case|java|url):(.*)$/);
    return match ? { type: match[1], value: match[2] } : { type: "", value: value };
  }

  function repoRelativeTarget(value, fieldPath, forced) {
    if (!forced && !fieldPath.split(/[.\[]/).some(function (part) {
      const normalized = part.toLowerCase();
      return EVIDENCE_FIELDS.has(normalized) ||
        normalized.endsWith("_evidence") ||
        normalized.endsWith("_report");
    })) {
      return null;
    }
    let candidate = value.trim();
    if (!candidate || candidate.indexOf("\u0000") >= 0 || candidate.charAt(0) === "/" || /^[A-Za-z][A-Za-z0-9+.-]*:/.test(candidate)) {
      return null;
    }
    let fragment = "";
    const hashIndex = candidate.indexOf("#");
    if (hashIndex >= 0) {
      fragment = candidate.slice(hashIndex + 1);
      candidate = candidate.slice(0, hashIndex);
    }
    const lineMatch = candidate.match(/^(.*):(\d+)$/);
    if (lineMatch) {
      candidate = lineMatch[1];
      fragment = fragment || "L" + lineMatch[2];
    }
    const parts = candidate.split("/");
    if (!parts.length || parts.some(function (part) { return !part || part === "." || part === ".."; })) {
      return null;
    }
    const looksLikePath = parts.length > 1 || /\.[A-Za-z0-9_-]+$/.test(parts[0]);
    if (!looksLikePath) {
      return null;
    }
    const encodedPath = parts.map(encodeURIComponent).join("/");
    return "../../" + encodedPath + (fragment ? "#" + encodeURIComponent(fragment) : "");
  }

  function emptyState(message) {
    return element("div", { className: "empty-state" }, [
      element("span", { "aria-hidden": "true", text: "○" }),
      element("p", { text: message })
    ]);
  }

  function clearFilters() {
    dom.search.value = "";
    dom.kind.value = "";
    dom.axis.value = "";
    dom.classification.value = "";
    dom.coverage.value = "";
    dom.status.value = "";
    syncFiltersFromDom();
    render();
    dom.search.focus();
  }

  [dom.kind, dom.axis, dom.classification, dom.coverage, dom.status].forEach(function (control) {
    control.addEventListener("change", function () {
      syncFiltersFromDom();
      render();
    });
  });
  dom.search.addEventListener("input", function () {
    syncFiltersFromDom();
    render();
  });
  dom.clear.addEventListener("click", clearFilters);
  dom.reload.addEventListener("click", loadCatalog);
  window.addEventListener("hashchange", render);

  if (!window.location.hash) {
    window.history.replaceState(null, "", "#/dashboard");
  }
  loadCatalog();
}());
