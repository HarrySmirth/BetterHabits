// Deletes the caller's own account.
// 1. prepare_account_deletion() (run as the caller) deletes households where they are the only
//    member and refuses if they own a shared household (ownership must be transferred first).
// 2. The auth user is deleted; profile and memberships cascade.
//
// POST {} -> { ok: true } | 409 { error: "transfer_ownership_required", households: "A, B" }
import { adminClient, authenticate, error, json } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return error(405, "method_not_allowed");
  const caller = await authenticate(req);
  if (!caller) return error(401, "not_authenticated");

  const { error: prepError } = await caller.asCaller.rpc("prepare_account_deletion");
  if (prepError) {
    if (prepError.message?.includes("transfer_ownership_required")) {
      return error(409, "transfer_ownership_required", { households: prepError.details ?? "" });
    }
    if (prepError.code === "42501") return error(403, "permission_denied");
    console.error("prepare_account_deletion failed", prepError);
    return error(500, "account_delete_failed");
  }

  const { error: deleteError } = await adminClient().auth.admin.deleteUser(caller.user.id);
  if (deleteError) {
    console.error("delete user failed", deleteError);
    return error(500, "account_delete_failed");
  }
  return json(200, { ok: true });
});
