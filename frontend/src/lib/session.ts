"use client";

import { useCallback, useEffect, useState } from "react";
import { getJson } from "./api";

export type SessionUser = {
  id: number;
  email: string;
  role: string;
  emailVerified: boolean;
  mustChangePassword?: boolean;
  hasProfile?: boolean;
  /**
   * Billing permission codes. The nav gates its destinations on these, and every
   * write control asks before it renders.
   */
  permissions?: string[];
};

/**
 * Whether the caller holds a billing permission.
 *
 * A write control that would only come back 403 is not offered at all — the read
 * endpoints answer an empty list rather than refusing, so the screens used to
 * show actions nobody could take. The server still checks every route, so this
 * decides what is shown, never what is allowed.
 *
 * `permissions` is undefined until the session resolves, and these controls only
 * render afterwards, since their data is fetched with the same token. So an
 * undefined list here means something went wrong rather than that we are early,
 * and the answer is no. A missing action is recoverable, an action that always
 * fails reads as a broken screen.
 */
export function allows(user: SessionUser | null, permission: string): boolean {
  return user?.permissions?.includes(permission) ?? false;
}

/**
 * The bootstrap every signed-in billing page needs: no token means back to
 * login, a temporary password means /change-password first, no profile means
 * /profile first. The backend enforces all three, so these redirects are for the
 * user's benefit rather than the security boundary.
 *
 * `withToken` reads the stored token at call time rather than closing over one
 * from render, so a JWT renewed by a previous response is the one used next.
 */
export function useSession() {
  const [me, setMe] = useState<SessionUser | null>(null);

  useEffect(() => {
    void (async () => {
      const stored = localStorage.getItem("auth_token");
      if (!stored) {
        window.location.href = "/";
        return;
      }
      // This is the page's own load, so a failure redirects instead of alerting.
      const session = await getJson<{ user: SessionUser }>(stored, "/api/me");
      if (!session.ok) {
        localStorage.removeItem("auth_token");
        window.location.href = "/";
        return;
      }
      if (session.data.user.mustChangePassword) {
        window.location.href = "/change-password";
        return;
      }
      if (!session.data.user.hasProfile) {
        window.location.href = "/profile";
        return;
      }
      setMe(session.data.user);
    })();
  }, []);

  const withToken = useCallback((run: (authToken: string) => Promise<void>) => {
    const authToken = localStorage.getItem("auth_token");
    if (authToken) void run(authToken);
  }, []);

  return { me, withToken };
}
