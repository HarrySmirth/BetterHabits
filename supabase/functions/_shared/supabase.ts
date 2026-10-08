// Shared helpers for Edge Functions. The service-role key is only ever read from the function
// environment (set by Supabase), never shipped to clients.
import { createClient, type SupabaseClient, type User } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function error(status: number, code: string, extra: Record<string, unknown> = {}): Response {
  return json(status, { error: code, ...extra });
}

export interface Caller {
  user: User;
  /** Client acting as the caller: RLS and RPC permission checks apply. */
  asCaller: SupabaseClient;
}

/** Verifies the bearer token and returns the caller, or null if the request is unauthenticated. */
export async function authenticate(req: Request): Promise<Caller | null> {
  const header = req.headers.get("Authorization") ?? "";
  if (!header.startsWith("Bearer ")) return null;
  const asCaller = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    global: { headers: { Authorization: header } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await asCaller.auth.getUser(header.slice("Bearer ".length));
  if (error || !data.user) return null;
  return { user: data.user, asCaller };
}

/** Privileged client. Use only after the caller's permissions have been checked. */
export function adminClient(): SupabaseClient {
  return createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
