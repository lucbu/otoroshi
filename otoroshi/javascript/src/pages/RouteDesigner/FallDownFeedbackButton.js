import React, { useEffect, useState } from 'react';

export function FeedbackButton({
  type = 'save',
  text,
  icon,
  onPress = () => Promise.resolve(),
  feedbackTimeout = 1500,
  className,
  disabled,
  style = {},
}) {
  const [uploading, setUploading] = useState(false);
  const [result, onResult] = useState('waiting');
  const [color, setColor] = useState(`btn-${type}`);

  useEffect(() => {
    let timeout;

    if (result !== 'waiting') {
      setUploading(false);
      timeout = setTimeout(() => {
        onResult('waiting');
      }, feedbackTimeout);
    }

    return () => {
      if (timeout) clearTimeout(timeout);
    };
  }, [result]);

  const failed = result === 'failed';
  const successed = result === 'success';
  const waiting = result === 'waiting';
  const loading = waiting && uploading;

  const Icon = icon;

  useEffect(() => {
    setColor(getColor());
  }, [result, uploading]);

  const getColor = () => {
    if (successed) return 'btn-save';
    else if (failed) return 'btn-danger';
    else if (loading) return 'btn-secondary';

    return `btn-${type}`;
  };

  const [fall, setFall] = useState(null);
  const [enabledFalldown, setEnabledFalldown] = useState(false);

  function getOffset(el) {
    const rect = el.getBoundingClientRect();
    return {
      left: rect.left + window.scrollX,
      top: rect.top + window.scrollY,
    };
  }

  return (
    <>
      {fall && (
        <button
          type="button"
          disabled={disabled}
          className={`btn ${color} ${className || ''} ${enabledFalldown ? 'fall-down' : null}`}
          style={{
            zIndex: 10000,
            position: 'absolute',
            top: `${fall.top}px`,
            left: `${fall.left}px`,
          }}
          // onClick={() => {

          // }}
        >
          <div
            className="me-1"
            style={{
              width: '16px',
              display: 'inline-block',
            }}>
            {waiting && !uploading && <Icon />}

            {loading && (
              <i
                className="fas fa-spinner fa-spin fa-sm"
                style={{
                  opacity: loading ? 1 : 0,
                  transition: 'opacity 2s',
                }}
              />
            )}

            {successed && (
              <i
                className="fas fa-check"
                style={{
                  opacity: successed ? 1 : 0,
                  transition: 'opacity 2s',
                }}
              />
            )}

            {failed && (
              <i
                className="fas fa-times"
                style={{
                  opacity: failed ? 1 : 0,
                  transition: 'opacity 2s',
                }}
              />
            )}
          </div>
          {text}
        </button>
      )}
      <button
        id={text}
        type="button"
        disabled={disabled}
        className={`btn ${color} ${className || ''}`}
        style={{
          ...style,
          opacity: fall ? 0 : 1,
        }}
        onClick={() => {
          setFall(getOffset(document.getElementById(text)));
          setTimeout(() => setEnabledFalldown(true), 50);

          // if (!uploading && waiting) {
          //   setUploading(true);
          //   const timer = Date.now();
          //   onPress()
          //     .then(() => {
          //       const diff = Date.now() - timer;
          //       if (diff > 150) onResult('success');
          //       setTimeout(() => {
          //         onResult('success');
          //       }, 150 - diff);
          //     })
          //     .catch((err) => {
          //       onResult('failed');
          //       throw err;
          //     });
          // }
        }}>
        <div
          className="me-1"
          style={{
            width: '16px',
            display: 'inline-block',
          }}>
          {waiting && !uploading && <Icon />}

          {loading && (
            <i
              className="fas fa-spinner fa-spin fa-sm"
              style={{
                opacity: loading ? 1 : 0,
                transition: 'opacity 2s',
              }}
            />
          )}

          {successed && (
            <i
              className="fas fa-check"
              style={{
                opacity: successed ? 1 : 0,
                transition: 'opacity 2s',
              }}
            />
          )}

          {failed && (
            <i
              className="fas fa-times"
              style={{
                opacity: failed ? 1 : 0,
                transition: 'opacity 2s',
              }}
            />
          )}
        </div>
        {text}
      </button>
    </>
  );
}
