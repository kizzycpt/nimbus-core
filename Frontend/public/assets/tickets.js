/* Help / change-request tickets. */

const STATUS_LABEL = {
  OPEN: "Open",
  IN_PROGRESS: "In progress",
  CLOSED: "Closed"
};

let openTicketId = null;

async function loadTickets() {
  const list = $("#ticketList");
  const tickets = await apiFetch("/tickets");

  if (!tickets.length) {
    list.innerHTML = `<div class="toast">No tickets yet. Raise one on the left.</div>`;
    return;
  }

  list.innerHTML = tickets.map(t => `
    <div class="toast">
      <div>
        <a href="#" class="mono" data-open="${esc(t.id)}">#${esc(t.id)} ${esc(t.subject)}</a>
      </div>
      <div class="small">
        <span class="badge">${esc(t.kind)}</span>
        <span class="badge ${t.status === "CLOSED" ? "" : "ok"}">${esc(STATUS_LABEL[t.status] || t.status)}</span>
        · ${esc(t.commentCount)} comment(s)
        · updated ${esc(formatTime(t.updatedAt))}
      </div>
    </div>
  `).join("");
}

async function openTicket(id) {
  const panel = $("#ticketDetail");
  const ticket = await apiFetch(`/tickets/${id}`);
  openTicketId = ticket.id;

  const comments = ticket.comments.length
    ? ticket.comments.map(c => `
        <div class="toast">
          <div class="small mono">${esc(c.author)} · ${esc(formatTime(c.createdAt))}</div>
          <div>${esc(c.body)}</div>
        </div>`).join("")
    : `<div class="small">No replies yet.</div>`;

  panel.innerHTML = `
    <h2>#${esc(ticket.id)} ${esc(ticket.subject)}</h2>
    <div class="small">
      <span class="badge">${esc(ticket.kind)}</span>
      <span class="badge">${esc(STATUS_LABEL[ticket.status] || ticket.status)}</span>
      · opened ${esc(formatTime(ticket.createdAt))}
    </div>
    <hr />
    <div class="toast">${esc(ticket.body)}</div>

    <h3 class="mt-14">Replies</h3>
    ${comments}

    <form id="commentForm" class="form mt-12">
      <textarea class="input" id="commentBody" rows="3"
                placeholder="Add a reply" required></textarea>
      <button class="btn btn-primary" type="submit">Reply</button>
    </form>
    <div id="detailToast" class="toast" hidden></div>

    <hr />
    <div class="row">
      <button class="btn" data-status="OPEN">Reopen</button>
      <button class="btn" data-status="IN_PROGRESS">In progress</button>
      <button class="btn" data-status="CLOSED">Close</button>
      <button class="btn btn-danger" id="deleteTicketBtn">Delete</button>
    </div>
  `;
  panel.hidden = false;

  $("#commentForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const toast = $("#detailToast");
    try {
      await apiFetch(`/tickets/${openTicketId}/comments`, {
        method: "POST",
        body: JSON.stringify({ body: $("#commentBody").value })
      });
      await openTicket(openTicketId);
      await loadTickets();
    } catch (err) {
      setToast(toast, err.message, false);
    }
  });

  panel.querySelectorAll("[data-status]").forEach(btn => {
    btn.addEventListener("click", async () => {
      try {
        await apiFetch(`/tickets/${openTicketId}`, {
          method: "PATCH",
          body: JSON.stringify({ status: btn.dataset.status })
        });
        await openTicket(openTicketId);
        await loadTickets();
      } catch (err) {
        setToast($("#detailToast"), err.message, false);
      }
    });
  });

  $("#deleteTicketBtn").addEventListener("click", async () => {
    try {
      await apiFetch(`/tickets/${openTicketId}`, { method: "DELETE" });
      panel.hidden = true;
      openTicketId = null;
      await loadTickets();
    } catch (err) {
      setToast($("#detailToast"), err.message, false);
    }
  });
}

window.addEventListener("DOMContentLoaded", async () => {
  const user = await requireUser();
  if (!user) return;

  await loadTickets();

  $("#ticketForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const toast = $("#ticketToast");
    try {
      await apiFetch("/tickets", {
        method: "POST",
        body: JSON.stringify({
          subject: $("#subject").value,
          body: $("#body").value,
          kind: $("#kind").value
        })
      });
      setToast(toast, "Ticket raised.", true);
      $("#ticketForm").reset();
      await loadTickets();
    } catch (err) {
      setToast(toast, err.message, false);
    }
  });

  $("#ticketList").addEventListener("click", (e) => {
    const id = e.target.dataset.open;
    if (!id) return;
    e.preventDefault();
    openTicket(id).catch(err => setToast($("#ticketToast"), err.message, false));
  });
});
