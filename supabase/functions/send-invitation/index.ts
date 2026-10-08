// Emails a household invitation that the caller has already created (create_invitation RPC).
// The invitation itself is the source of truth: the invitee accepts it in-app after signing in
// with the invited address. This only notifies them.
//
// POST { invitation_id } -> { ok: true }
//   403 permission_denied     caller can't see the invitation (RLS: needs INVITE_MEMBERS)
//   429 rate_limited          more than DAILY_LIMIT invitation emails from this user in 24h
//   503 email_not_configured  RESEND_API_KEY function secret is missing
import { adminClient, authenticate, error, json, UUID_PATTERN } from "../_shared/supabase.ts";

const RESEND_API_KEY = Deno.env.get("RESEND_API_KEY");
const FROM = Deno.env.get("INVITE_FROM_EMAIL") ?? "BetterHabits <no-reply@harry-smith.uk>";
const DOWNLOAD_URL = Deno.env.get("APP_DOWNLOAD_URL") ?? "https://github.com/HarrySmirth/BetterHabits/releases/latest";
const DAILY_LIMIT = 20;

Deno.serve(async (req) => {
  if (req.method !== "POST") return error(405, "method_not_allowed");
  const caller = await authenticate(req);
  if (!caller) return error(401, "not_authenticated");
  if (!RESEND_API_KEY) return error(503, "email_not_configured");

  let invitationId: unknown;
  try {
    invitationId = (await req.json()).invitation_id;
  } catch {
    return error(400, "invalid_request");
  }
  if (typeof invitationId !== "string" || !UUID_PATTERN.test(invitationId)) return error(400, "invalid_request");

  // Read as the caller: RLS only returns the invitation if they hold INVITE_MEMBERS for its household.
  const { data: invitation } = await caller.asCaller
    .from("household_invitations")
    .select("id, email, status, household_id, households(name)")
    .eq("id", invitationId)
    .maybeSingle();
  if (!invitation || invitation.status !== "PENDING") return error(403, "permission_denied");

  const admin = adminClient();
  const since = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
  const { count } = await admin
    .from("household_invitations")
    .select("id", { count: "exact", head: true })
    .eq("invited_by", caller.user.id)
    .gte("created_at", since);
  if ((count ?? 0) > DAILY_LIMIT) return error(429, "rate_limited");

  const { data: profile } = await admin.from("profiles").select("display_name").eq("id", caller.user.id).maybeSingle();
  const inviter = profile?.display_name ?? "Someone";
  // deno-lint-ignore no-explicit-any
  const householdName = (invitation as any).households?.name ?? "their household";

  const response = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { Authorization: `Bearer ${RESEND_API_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      from: FROM,
      to: [invitation.email],
      subject: `${inviter} invited you to ${householdName} on BetterHabits`,
      text: textBody(inviter, householdName, invitation.email),
      html: htmlBody(inviter, householdName, invitation.email),
    }),
  });
  if (!response.ok) {
    console.error("resend failed", response.status, await response.text());
    return error(502, "email_send_failed");
  }
  return json(200, { ok: true });
});

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

function textBody(inviter: string, household: string, email: string): string {
  return [
    `${inviter} invited you to join "${household}" on BetterHabits, an app for sharing household chores fairly.`,
    ``,
    `To accept: install BetterHabits (${DOWNLOAD_URL}), then sign in or create an account with ${email}.`,
    `The invitation will be waiting for you. It expires in 14 days.`,
    ``,
    `If you weren't expecting this, you can ignore this email.`,
  ].join("\n");
}

function htmlBody(inviter: string, household: string, email: string): string {
  const i = escapeHtml(inviter);
  const h = escapeHtml(household);
  const e = escapeHtml(email);
  return `<h2>You're invited to ${h}</h2>
<p>${i} invited you to join <strong>${h}</strong> on BetterHabits, an app for sharing household chores fairly.</p>
<p>To accept, <a href="${escapeHtml(DOWNLOAD_URL)}">install BetterHabits</a>, then sign in or create an account with <strong>${e}</strong>. The invitation will be waiting for you. It expires in 14 days.</p>
<p style="color:#666">If you weren't expecting this, you can ignore this email.</p>`;
}
