// Sign-in / register form wiring. Kept out of login.html's <script> tag —
// the server's CSP (SecurityHeadersFilter) is `default-src 'self'` with no
// `unsafe-inline`, so an inline <script> is silently blocked by every real
// browser (design doc section 8, "the client's own textContent-only
// rendering rule" backstop — external files are the other half of that).
(function () {
  const form = document.getElementById('auth-form');
  const title = document.getElementById('auth-title');
  const submitBtn = document.getElementById('auth-submit');
  const toggleBtn = document.getElementById('auth-toggle');
  const errorEl = document.getElementById('auth-error');
  const displayNameField = document.getElementById('display-name-field');
  const displayNameInput = document.getElementById('displayName');
  let mode = 'login';

  function setMode(next) {
    mode = next;
    const isRegister = mode === 'register';
    title.textContent = isRegister ? 'Create an account' : 'Sign in';
    submitBtn.textContent = isRegister ? 'Register' : 'Sign in';
    toggleBtn.textContent = isRegister ? 'Already have an account? Sign in instead' : 'Need an account? Register instead';
    displayNameField.hidden = !isRegister;
    displayNameInput.required = isRegister;
    errorEl.textContent = '';
  }

  toggleBtn.addEventListener('click', () => setMode(mode === 'login' ? 'register' : 'login'));

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    errorEl.textContent = '';
    submitBtn.disabled = true;
    const email = document.getElementById('email').value;
    const password = document.getElementById('password').value;

    try {
      if (mode === 'register') {
        await Api.register(email, password, displayNameInput.value);
      } else {
        await Api.login(email, password);
      }
      window.location.href = '/';
    } catch (err) {
      if (err instanceof Api.ApiError) {
        if (err.status === 423) {
          errorEl.textContent = 'Account temporarily locked after too many failed attempts. Try again later.';
        } else if (err.status === 429) {
          errorEl.textContent = 'Too many attempts. Try again shortly.';
        } else if (err.code === 'EMAIL_TAKEN') {
          errorEl.textContent = 'An account with that email already exists.';
        } else if (err.code === 'VALIDATION_FAILED' && err.body && Array.isArray(err.body.errors)) {
          errorEl.textContent = err.body.errors.map((v) => v.message).join(' ');
        } else {
          errorEl.textContent = 'Invalid email or password.';
        }
      } else {
        errorEl.textContent = 'Something went wrong. Try again.';
      }
    } finally {
      submitBtn.disabled = false;
    }
  });
})();
