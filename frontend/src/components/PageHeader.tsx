import type { ReactNode } from "react";

// Reusable page header. The title and optional subtitle render on the left, the
// page's navigation sits beside them as a <nav> landmark, and children (e.g. a
// logout link) sit on the right. Keeping the nav a separate slot rather than a
// pile of loose links is what lets CSS group it and tell it apart from the
// logout link — matching on "the last child" cannot tell them apart, since
// Logout is a link too.
export function PageHeader({
  title,
  subtitle,
  nav,
  children,
}: {
  title: string;
  subtitle?: ReactNode;
  nav?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle ? <p>{subtitle}</p> : null}
      </div>
      {nav ? <nav className="page-nav">{nav}</nav> : null}
      {children}
    </header>
  );
}
