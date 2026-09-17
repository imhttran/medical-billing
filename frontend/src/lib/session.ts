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
  /** Billing permission codes, which the nav gates its destinations on. */
  permissions?: string[];
};

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
