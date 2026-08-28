/* Website hosting: provisioning, uploads, quota. */

let currentSlug = null;

function quotaBar(percent) {
  // The width has to be data-driven. A style="" attribute in markup would be
  // blocked by style-src 'self', but assigning through the CSSOM the way this
  // does is not considered an inline style, so the strict CSP stays intact.
  const bar = document.createElement("div");
  bar.className = "meter";
  const fill = document.createElement("div");
  fill.className = "meter-fill" + (percent >= 90 ? " meter-full" : "");
  fill.style.width = Math.max(2, percent) + "%";
  bar.appendChild(fill);
  return bar;
}

async function loadSites() {
  const data = await apiFetch("/sites");

  $("#quotaText").textContent =
    `${data.usedHuman} of ${data.quotaHuman} used (${data.percentUsed}%)`;
  const holder = $("#quotaMeter");
  holder.innerHTML = "";
  holder.appendChild(quotaBar(data.percentUsed));

  $("#siteLimit").textContent = `${data.sites.length} of ${data.maxSites} sites`;
  $("#newSiteForm").hidden = data.sites.length >= data.maxSites;

  const list = $("#siteList");
  if (!data.sites.length) {
    list.innerHTML = `<div class="toast">No sites yet. Create one to get started.</div>`;
    $("#fileCard").hidden = true;
    return;
  }

  list.innerHTML = data.sites.map(s => `
    <div class="toast">
      <div>
        <a class="mono" href="${esc(s.url)}" target="_blank" rel="noopener noreferrer">${esc(s.slug)}</a>
        <span class="badge ok">live</span>
      </div>
      <div class="small">
        ${esc(s.fileCount)} file(s) · ${esc(s.usedHuman)} · updated ${esc(formatTime(s.updatedAt))}
      </div>
      <div class="row mt-10">
        <button class="btn" data-manage="${esc(s.slug)}">Manage files</button>
        <button class="btn btn-danger" data-delete="${esc(s.slug)}">Delete site</button>
      </div>
    </div>
  `).join("");
}

async function loadFiles(slug) {
  currentSlug = slug;
  const files = await apiFetch(`/sites/${slug}/files`);

  $("#fileCard").hidden = false;
  $("#fileSiteName").textContent = slug;
  $("#siteLink").href = `/s/${slug}/`;
  $("#siteLink").textContent = `/s/${slug}/`;

  const list = $("#fileList");
  list.innerHTML = files.length
    ? files.map(f => `
        <div class="toast">
          <div class="mono">${esc(f.path)}</div>
          <div class="small">${esc(f.sizeHuman)} · ${esc(formatTime(f.modifiedAt))}</div>
          <div class="row mt-10">
            <button class="btn btn-danger" data-rm="${esc(f.path)}">Delete</button>
          </div>
        </div>`).join("")
    : `<div class="toast">No files yet.</div>`;
}

window.addEventListener("DOMContentLoaded", async () => {
  const user = await requireUser();
  if (!user) return;

  await loadSites();

  // ------------------------------------------------------------ create
  $("#newSiteForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const toast = $("#siteToast");
    try {
      const res = await apiFetch("/sites", {
        method: "POST",
        body: JSON.stringify({ slug: $("#slug").value.trim().toLowerCase() })
      });
      setToast(toast, `Created. It goes live at ${res.url} within a minute.`, true);
      $("#newSiteForm").reset();
      await loadSites();
    } catch (err) {
      setToast(toast, err.message, false);
    }
  });

  // ------------------------------------------------- manage / delete site
  $("#siteList").addEventListener("click", async (e) => {
    const manage = e.target.dataset.manage;
    const remove = e.target.dataset.delete;
    const toast = $("#siteToast");

    if (manage) {
      try { await loadFiles(manage); }
      catch (err) { setToast(toast, err.message, false); }
      return;
    }

    if (remove) {
      if (!confirm(`Delete "${remove}" and everything in it? This cannot be undone.`)) return;
      try {
        await apiFetch(`/sites/${remove}`, { method: "DELETE" });
        setToast(toast, `Deleted ${remove}.`, true);
        if (currentSlug === remove) $("#fileCard").hidden = true;
        await loadSites();
      } catch (err) {
        setToast(toast, err.message, false);
      }
    }
  });

  // ------------------------------------------------------------ upload
  $("#uploadForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const toast = $("#uploadToast");
    const input = $("#fileInput");

    if (!input.files.length) {
      setToast(toast, "Choose a file first", false);
      return;
    }

    // FormData sets its own multipart Content-Type with the boundary, so this
    // deliberately bypasses apiFetch's JSON header while keeping the CSRF token.
    const form = new FormData();
    form.append("file", input.files[0]);
    const destination = $("#destPath").value.trim();
    if (destination) form.append("path", destination);

    try {
      const token = await ensureCsrfToken();
      const res = await fetch(`${API_BASE}/sites/${currentSlug}/files`, {
        method: "POST",
        credentials: "same-origin",
        headers: token ? { "X-XSRF-TOKEN": token } : {},
        body: form
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) throw new Error(data && data.message ? data.message : `HTTP ${res.status}`);

      setToast(toast, `Uploaded ${data.path} (${data.usedHuman} used)`, true);
      $("#uploadForm").reset();
      await loadFiles(currentSlug);
      await loadSites();
    } catch (err) {
      setToast(toast, err.message, false);
    }
  });

  // ------------------------------------------------------- delete a file
  $("#fileList").addEventListener("click", async (e) => {
    const path = e.target.dataset.rm;
    if (!path) return;
    const toast = $("#uploadToast");
    try {
      await apiFetch(`/sites/${currentSlug}/files/${path}`, { method: "DELETE" });
      setToast(toast, `Deleted ${path}`, true);
      await loadFiles(currentSlug);
      await loadSites();
    } catch (err) {
      setToast(toast, err.message, false);
    }
  });
});
