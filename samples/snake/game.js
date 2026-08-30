(function () {
  'use strict';

  var CELLS = 17;          // board is CELLS x CELLS
  var SPEED_START = 160;   // ms per step
  var SPEED_MIN = 70;

  var canvas = document.getElementById('board');
  var ctx = canvas.getContext('2d');
  var scoreEl = document.getElementById('score');
  var bestEl = document.getElementById('best');
  var msgEl = document.getElementById('msg');
  var startBtn = document.getElementById('start');

  var best = Number(localStorage.getItem('snake.best') || 0);
  bestEl.textContent = String(best);

  var snake, dir, nextDir, food, score, speed, timer, running;

  // The canvas is square and sized in CSS pixels by layout; back it with the
  // device pixel ratio so the cells stay crisp on a 3x screen.
  function fit() {
    var css = Math.min(canvas.parentNode.clientWidth, window.innerHeight - 200);
    css = Math.max(200, Math.floor(css));
    var dpr = window.devicePixelRatio || 1;
    canvas.style.width = css + 'px';
    canvas.style.height = css + 'px';
    canvas.width = Math.round(css * dpr);
    canvas.height = Math.round(css * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    draw();
  }

  function reset() {
    snake = [{ x: 8, y: 8 }, { x: 7, y: 8 }, { x: 6, y: 8 }];
    dir = { x: 1, y: 0 };
    nextDir = dir;
    score = 0;
    speed = SPEED_START;
    placeFood();
    scoreEl.textContent = '0';
  }

  function placeFood() {
    var free = [];
    for (var y = 0; y < CELLS; y++) {
      for (var x = 0; x < CELLS; x++) {
        if (!hits(x, y, snake)) free.push({ x: x, y: y });
      }
    }
    food = free[Math.floor(Math.random() * free.length)];
  }

  function hits(x, y, body) {
    for (var i = 0; i < body.length; i++) {
      if (body[i].x === x && body[i].y === y) return true;
    }
    return false;
  }

  function step() {
    dir = nextDir;
    // Walls wrap: leaving an edge comes back on the opposite one, so the only
    // way to lose is running into yourself.
    var head = {
      x: (snake[0].x + dir.x + CELLS) % CELLS,
      y: (snake[0].y + dir.y + CELLS) % CELLS
    };

    if (hits(head.x, head.y, snake.slice(0, -1))) return gameOver();

    snake.unshift(head);
    if (head.x === food.x && head.y === food.y) {
      score += 1;
      scoreEl.textContent = String(score);
      if (score > best) {
        best = score;
        bestEl.textContent = String(best);
        localStorage.setItem('snake.best', String(best));
      }
      if (speed > SPEED_MIN) {
        speed -= 4;
        clearInterval(timer);
        timer = setInterval(step, speed);
      }
      placeFood();
    } else {
      snake.pop();
    }
    draw();
  }

  function draw() {
    if (!snake) return;
    var size = canvas.width / (window.devicePixelRatio || 1);
    var cell = size / CELLS;

    ctx.fillStyle = '#0f1a14';
    ctx.fillRect(0, 0, size, size);

    ctx.fillStyle = '#16261d';
    for (var y = 0; y < CELLS; y++) {
      for (var x = (y % 2); x < CELLS; x += 2) {
        ctx.fillRect(x * cell, y * cell, cell, cell);
      }
    }

    ctx.fillStyle = '#e95454';
    round(food.x * cell, food.y * cell, cell, cell * 0.45);

    for (var i = snake.length - 1; i >= 0; i--) {
      ctx.fillStyle = i === 0 ? '#b7f2c2' : '#7cd689';
      round(snake[i].x * cell, snake[i].y * cell, cell, cell * 0.3);
    }
  }

  function round(x, y, size, r) {
    var p = size * 0.08;
    var w = size - p * 2;
    ctx.beginPath();
    ctx.moveTo(x + p + r, y + p);
    ctx.arcTo(x + p + w, y + p, x + p + w, y + p + w, r);
    ctx.arcTo(x + p + w, y + p + w, x + p, y + p + w, r);
    ctx.arcTo(x + p, y + p + w, x + p, y + p, r);
    ctx.arcTo(x + p, y + p, x + p + w, y + p, r);
    ctx.fill();
  }

  function start() {
    reset();
    running = true;
    msgEl.textContent = '';
    startBtn.textContent = 'やり直す';
    clearInterval(timer);
    timer = setInterval(step, speed);
    draw();
  }

  function gameOver() {
    running = false;
    clearInterval(timer);
    msgEl.textContent = 'ゲームオーバー — スコア ' + score;
    draw();
  }

  function turn(x, y) {
    // Reversing straight into the neck is the usual way to die by accident.
    if (snake && (dir.x + x === 0 && dir.y + y === 0)) return;
    nextDir = { x: x, y: y };
    if (!running) start();
  }

  var KEYS = {
    ArrowUp: [0, -1], ArrowDown: [0, 1], ArrowLeft: [-1, 0], ArrowRight: [1, 0],
    w: [0, -1], s: [0, 1], a: [-1, 0], d: [1, 0]
  };
  window.addEventListener('keydown', function (e) {
    var k = KEYS[e.key];
    if (!k) return;
    e.preventDefault();
    turn(k[0], k[1]);
  });

  var pads = document.querySelectorAll('[data-dir]');
  for (var i = 0; i < pads.length; i++) {
    (function (btn) {
      btn.addEventListener('click', function () {
        var d = btn.getAttribute('data-dir').split(',');
        turn(Number(d[0]), Number(d[1]));
      });
    })(pads[i]);
  }

  // Swipe. touch-action is none on the canvas, so the page will not scroll.
  var from = null;
  canvas.addEventListener('touchstart', function (e) {
    from = { x: e.touches[0].clientX, y: e.touches[0].clientY };
  }, { passive: true });
  canvas.addEventListener('touchend', function (e) {
    if (!from) return;
    var t = e.changedTouches[0];
    var dx = t.clientX - from.x;
    var dy = t.clientY - from.y;
    from = null;
    if (Math.abs(dx) < 16 && Math.abs(dy) < 16) { if (!running) start(); return; }
    if (Math.abs(dx) > Math.abs(dy)) turn(dx > 0 ? 1 : -1, 0);
    else turn(0, dy > 0 ? 1 : -1);
  });

  startBtn.addEventListener('click', start);
  window.addEventListener('resize', fit);

  reset();
  fit();
  msgEl.textContent = 'スワイプかボタンで開始';
})();
