import React from 'react';
import variables from '../style/_variables.scss';

export function Dropdown({ children, className = '', style = {}, buttonStyle }) {
  return (
    <div className={`dropdown ${className}`} style={style}>
      <button
        className="btn btn-sm toggle-form-buttons d-flex align-items-center dark-background"
        style={{
          backgroundColor: variables.fondNavbar,
          color: '#fff',
          height: '100%',
          ...(buttonStyle || {}),
        }}
        id="menu"
        data-bs-toggle="dropdown"
        data-bs-auto-close="outside"
        aria-expanded="false">
        <i className="fas fa-ellipsis-h" style={{ fontSize: '1.33333em' }} />
      </button>
      <ul
        className="dropdown-menu"
        aria-labelledby="menu"
        style={{
          background: variables.fondNavbar,
          border: '1px solid #373735',
          borderTop: 0,
          padding: '12px',
          zIndex: 4000,
        }}
        onClick={(e) => e.stopPropagation()}>
        <li
          className="d-flex flex-wrap"
          style={{
            gap: '8px',
            minWidth: '170px',
          }}>
          {children}
        </li>
      </ul>
    </div>
  );
}
