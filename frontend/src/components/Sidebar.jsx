import { NavLink } from "react-router-dom";

export default function Sidebar({ open, onClose, fileCount, activePage = "dashboard" }) {
  return (
    <>
      <aside className={`sidebar ${open ? "open" : ""}`}>
        <nav>
          <NavLink className={`side-link ${activePage === "dashboard" ? "active" : ""}`} to="/dashboard" onClick={onClose}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 12l9-9 9 9M5 10v10h14V10" /></svg>
            Dashboard
          </NavLink>
          <a className="side-link" href="/dashboard#upload" onClick={onClose}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 16V4M6 10l6-6 6 6M4 20h16" /></svg>
            Upload
          </a>
          <a className="side-link" href="/dashboard#files" onClick={onClose}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z M14 3v6h6" /></svg>
            My files
            <span className="pill">{fileCount}</span>
          </a>
          <NavLink className="side-link" to="/remote" onClick={onClose}>
            Remote access
          </NavLink>
          <NavLink className={`side-link ${activePage === "clipbox" ? "active" : ""}`} to="/clipbox" onClick={onClose}>
            Clipbox
          </NavLink>
        </nav>
      </aside>
      {open && <div className="scrim" onClick={onClose} />}
    </>
  );
}
