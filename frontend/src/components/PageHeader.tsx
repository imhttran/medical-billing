import type { ReactNode } from "react";

// Every signed-in page offers the same destinations, and hand-writing that list
// per page is how they drifted apart — from some pages you couldn't reach the
// dashboard or the audit trail at all. So the list lives here, once.
const DESTINATIONS = [
  { href: "/claims", label: "Claims" },
  { href: "/patients", label: "Patients" },
  { href: "/work-queue", label: "Work queue" },
  { href: "/dashboard", label: "Dashboard" },
  { href: "/audit", label: "Audit trail" },
];

/**
 * The title and optional subtitle render on the left, the navigation sits beside
 * them as a <nav> landmark, and children (e.g. a logout link) sit on the right.
 *
 * `current` is the section the page belongs to, so the link for the page you are
 * already on is marked with aria-current rather than reading as somewhere to go.
 * Both records under /claims and /patients keep the section, not the exact route.
 */
export function PageHeader({
  title,
  subtitle,
  current,
  children,
}: {
  title: string;
  subtitle?: ReactNode;
  current: string;
  children?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle ? <p>{subtitle}</p> : null}
      </div>
      <nav className="page-nav">
        {DESTINATIONS.map(({ href, label }) => (
          <a
            key={href}
            href={href}
            aria-current={href === current ? "page" : undefined}
          >
            {label}
          </a>
        ))}
      </nav>
      {children}
    </header>
  );
}
