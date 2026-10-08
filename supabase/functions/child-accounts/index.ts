// Child accounts: created and managed by household owners/admins with MANAGE_CHILDREN.
// A child signs in with a generated username + a PIN chosen by the parent. Under the hood the
// username maps to an email on a non-routable domain, so no real inbox exists and no email is
// ever sent. Children cannot self-register, and this function refuses to touch adult accounts.
//
// POST { action: "create", household_id, display_name, pin }   -> { user_id, username }
// POST { action: "reset_pin", child_user_id, pin }             -> { ok: true }
// POST { action: "delete", child_user_id }                     -> { ok: true }
import { adminClient, authenticate, type Caller, error, json, UUID_PATTERN } from "../_shared/supabase.ts";

const CHILD_EMAIL_DOMAIN = Deno.env.get("CHILD_EMAIL_DOMAIN") ?? "children.betterhabits.invalid";
const USERNAME_SUFFIX_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
const MIN_PIN_LENGTH = 6;
const MAX_PIN_LENGTH = 72;

Deno.serve(async (req) => {
  if (req.method !== "POST") return error(405, "method_not_allowed");
  const caller = await authenticate(req);
  if (!caller) return error(401, "not_authenticated");

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return error(400, "invalid_request");
  }

  switch (body.action) {
    case "create":
      return await createChild(caller, body);
    case "reset_pin":
      return await resetPin(caller, body);
    case "delete":
      return await deleteChild(caller, body);
    default:
      return error(400, "invalid_request");
  }
});

function validPin(pin: unknown): pin is string {
  return typeof pin === "string" && pin.length >= MIN_PIN_LENGTH && pin.length <= MAX_PIN_LENGTH;
}

async function canManageChildren(caller: Caller, householdId: string): Promise<boolean> {
  const { data, error } = await caller.asCaller.rpc("has_household_permission", {
    p_household_id: householdId,
    p_permission: "MANAGE_CHILDREN",
  });
  return !error && data === true;
}

function generateUsername(displayName: string): string {
  const base = displayName.toLowerCase().normalize("NFKD").replace(/[^a-z]/g, "").slice(0, 12) || "child";
  const bytes = crypto.getRandomValues(new Uint8Array(4));
  const suffix = Array.from(bytes, (b) => USERNAME_SUFFIX_ALPHABET[b % USERNAME_SUFFIX_ALPHABET.length]).join("");
  return `${base}-${suffix}`;
}

async function createChild(caller: Caller, body: Record<string, unknown>): Promise<Response> {
  const householdId = body.household_id;
  const displayName = typeof body.display_name === "string" ? body.display_name.trim() : "";
  if (typeof householdId !== "string" || !UUID_PATTERN.test(householdId)) return error(400, "invalid_request");
  if (displayName.length < 1 || displayName.length > 50) return error(400, "invalid_display_name");
  if (!validPin(body.pin)) return error(400, "invalid_pin");
  if (!(await canManageChildren(caller, householdId))) return error(403, "permission_denied");

  const admin = adminClient();
  for (let attempt = 0; attempt < 5; attempt++) {
    const username = generateUsername(displayName);
    const { data, error: createError } = await admin.auth.admin.createUser({
      email: `${username}@${CHILD_EMAIL_DOMAIN}`,
      password: body.pin,
      email_confirm: true,
      app_metadata: { is_child: true },
      user_metadata: { display_name: displayName },
    });
    if (createError?.code === "email_exists") continue;
    if (createError || !data.user) {
      console.error("child create failed", createError);
      return error(500, "child_create_failed");
    }

    const childId = data.user.id;
    // Set the flag explicitly as well, in case app_metadata is applied after the insert trigger.
    const { error: profileError } = await admin.from("profiles").update({ is_child: true }).eq("id", childId);
    const { error: memberError } = await admin
      .from("household_members")
      .insert({ household_id: householdId, user_id: childId, role: "CHILD" });
    if (profileError || memberError) {
      console.error("child setup failed", profileError ?? memberError);
      await admin.auth.admin.deleteUser(childId);
      return error(500, "child_create_failed");
    }
    return json(200, { user_id: childId, username });
  }
  return error(500, "child_create_failed");
}

/** Households the target child belongs to, or null if the target isn't a child account. */
async function childHouseholds(childId: string): Promise<string[] | null> {
  const admin = adminClient();
  const { data: profile } = await admin.from("profiles").select("is_child").eq("id", childId).maybeSingle();
  if (!profile?.is_child) return null;
  const { data: memberships } = await admin
    .from("household_members")
    .select("household_id")
    .eq("user_id", childId)
    .eq("role", "CHILD");
  return (memberships ?? []).map((m) => m.household_id as string);
}

async function resetPin(caller: Caller, body: Record<string, unknown>): Promise<Response> {
  const childId = body.child_user_id;
  if (typeof childId !== "string" || !UUID_PATTERN.test(childId)) return error(400, "invalid_request");
  if (!validPin(body.pin)) return error(400, "invalid_pin");

  const households = await childHouseholds(childId);
  if (!households || households.length === 0) return error(403, "permission_denied");
  const allowed = await Promise.all(households.map((h) => canManageChildren(caller, h)));
  if (!allowed.some(Boolean)) return error(403, "permission_denied");

  const { error: updateError } = await adminClient().auth.admin.updateUserById(childId, { password: body.pin });
  if (updateError) {
    console.error("pin reset failed", updateError);
    return error(500, "pin_reset_failed");
  }
  return json(200, { ok: true });
}

async function deleteChild(caller: Caller, body: Record<string, unknown>): Promise<Response> {
  const childId = body.child_user_id;
  if (typeof childId !== "string" || !UUID_PATTERN.test(childId)) return error(400, "invalid_request");

  const households = await childHouseholds(childId);
  if (!households || households.length === 0) return error(403, "permission_denied");
  // Deleting removes the child from every household, so the caller must manage children in all of them.
  const allowed = await Promise.all(households.map((h) => canManageChildren(caller, h)));
  if (!allowed.every(Boolean)) return error(403, "permission_denied");

  const { error: deleteError } = await adminClient().auth.admin.deleteUser(childId);
  if (deleteError) {
    console.error("child delete failed", deleteError);
    return error(500, "child_delete_failed");
  }
  return json(200, { ok: true });
}
