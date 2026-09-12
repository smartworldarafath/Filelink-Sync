class Turn {
  _username = null;
  _credential = null;
  _expiration = null;

  getServers = async () => {
    try {
      await this._getCredentials();
    } catch (err) {
      console.warn('Custom TURN credentials not available, using public STUN fallback:', err);
    }

    if (this._username && this._credential) {
      const host = window.location.hostname;
      return [
        { urls: `stun:${host}:3478` },
        {
          urls: [
            `turn:${host}:3478`,
            `turn:${host}:3478?transport=tcp`,
          ],
          username: this._username,
          credential: this._credential,
        },
      ];
    }

    // Fallback STUN servers for serverless / GitHub Pages deployment
    return [
      { urls: 'stun:stun.l.google.com:19302' },
      { urls: 'stun:stun1.l.google.com:19302' },
      { urls: 'stun:stun2.l.google.com:19302' },
      { urls: 'stun:stun3.l.google.com:19302' },
      { urls: 'stun:stun4.l.google.com:19302' },
    ];
  }

  _getCredentials = async () => {
    const now = Math.floor(Date.now() / 1000);

    // Check if token is still valid
    if (this._expiration !== null && this._expiration > now) {
      return { username: this._username, credential: this._credential };
    }

    // Fetch new token
    const response = await fetch("/api/credentials", { method: "GET" });

    if (!response.ok) {
      throw new Error("An issue occurred while getting the token.");
    }

    // Parse the response as JSON
    const data = await response.json();

    const token = data.token
    if (!token) throw new Error("No token in credential response");

    // Decode JWT to get username and credential
    const payload = this._decodeJwt(token);

    // Set cached values
    this._username = payload.username;
    this._credential = payload.credential;
    this._expiration = payload.exp - 10; // Subtract 10 seconds for safety
  };

  _decodeJwt(token) {
    // JWTs are base64url-encoded (with padding stripped); normalize for atob().
    const parts = (token || '').split('.');
    if (parts.length < 2) throw new Error('Malformed JWT.');
    let b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4 !== 0) b64 += '=';
    return JSON.parse(atob(b64));
  }
}

export const turn = new Turn();