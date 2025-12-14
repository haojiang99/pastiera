<script>
  import { onMount } from 'svelte';

  let scrollY = 0;
  let visible = {
    hero: false,
    problem: false,
    features: false,
    juying: false,
    voice: false,
    learning: false,
    comparison: false,
    free: false,
    cta: false
  };

  onMount(() => {
    visible.hero = true;

    const observer = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          visible[entry.target.dataset.section] = true;
        }
      });
    }, { threshold: 0.1 });

    document.querySelectorAll('[data-section]').forEach(el => {
      observer.observe(el);
    });
  });

  const features = [
    {
      icon: '⌨️',
      title: '首字母输入',
      desc: '仅需输入首字母，按键减少50%-70%',
      example: '"你好我们今天开会" → nhwmjtkh'
    },
    {
      icon: '🎯',
      title: '巨硬模式',
      desc: 'Modifier键直选候选词，手不离键盘',
      example: 'Shift/Ctrl/Alt/Space 一键选词'
    },
    {
      icon: '🧠',
      title: '本地自学习',
      desc: '越用越懂你，所有学习在本地完成',
      example: '隐私安全，无需联网'
    },
    {
      icon: '🎙️',
      title: '离线语音识别',
      desc: '高精度普通话识别，完全本地运行',
      example: '无需网络，隐私无忧'
    },
    {
      icon: '🎨',
      title: '自定义主题',
      desc: '自由定制状态栏颜色，打造专属界面',
      example: '15种颜色可调 · 8款预设主题'
    },
    {
      icon: '🔊',
      title: '自定义音效',
      desc: '多种键盘音效可选，支持自定义音效',
      example: '机械/打字机/钢琴/马里奥等'
    }
  ];

  const comparisons = [
    { item: '设计重心', mainstream: '触屏输入', coolwulf: '实体键盘输入' },
    { item: '拼音方式', mainstream: '全拼为主', coolwulf: '首字母为核心' },
    { item: '选词方式', mainstream: '点击/数字/翻页', coolwulf: 'Modifier键直选' },
    { item: '是否离键', mainstream: '频繁', coolwulf: '极少' },
    { item: '习惯学习', mainstream: '云端部分', coolwulf: '本地深度学习' },
    { item: '语音识别', mainstream: '需要联网', coolwulf: '完全离线' },
    { item: '收费广告', mainstream: '广告+会员', coolwulf: '完全免费' }
  ];
</script>

<svelte:window bind:scrollY />

<main>
  <!-- Navigation -->
  <nav class:scrolled={scrollY > 50}>
    <div class="nav-content">
      <div class="logo">
        <span class="logo-icon">🐺</span>
        <span class="logo-text">酷狼输入法</span>
      </div>
      <div class="nav-links">
        <a href="#features">功能特色</a>
        <a href="#juying">巨硬模式</a>
        <a href="#voice">语音识别</a>
        <a href="/coolwulfIMEv0.80.1593.apk" class="btn-nav" download>立即下载</a>
      </div>
    </div>
  </nav>

  <!-- Hero Section -->
  <section class="hero" class:visible={visible.hero}>
    <div class="hero-bg">
      <div class="grid-overlay"></div>
      <div class="glow glow-1"></div>
      <div class="glow glow-2"></div>
    </div>
    <div class="hero-content">
      <div class="badge">🚀 专为实体键盘打造</div>
      <h1>
        <span class="gradient-text">酷狼输入法</span>
        <br />
        <span class="subtitle">让输入回归键盘的本质</span>
      </h1>
      <p class="hero-desc">
        一款真正为实体键盘效率而生的中文输入法<br />
        首字母输入 · 巨硬模式 · 本地自学习 · 离线语音<br />
        <strong>永久免费 · 无广告 · 隐私安全</strong>
      </p>
      <div class="hero-buttons">
        <a href="/coolwulfIMEv0.80.1593.apk" class="btn btn-primary" download>
          <span>立即下载</span>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/>
          </svg>
        </a>
        <a href="#features" class="btn btn-secondary">了解更多</a>
      </div>
      <div class="hero-stats">
        <div class="stat">
          <span class="stat-value">50-70%</span>
          <span class="stat-label">按键减少</span>
        </div>
        <div class="stat">
          <span class="stat-value">100%</span>
          <span class="stat-label">本地运行</span>
        </div>
        <div class="stat">
          <span class="stat-value">0</span>
          <span class="stat-label">广告/收费</span>
        </div>
      </div>
    </div>
    <div class="hero-visual">
      <div class="keyboard-mockup">
        <div class="keyboard-row">
          {#each ['Q','W','E','R','T','Y','U','I','O','P'] as key}
            <div class="key">{key}</div>
          {/each}
        </div>
        <div class="keyboard-row">
          {#each ['A','S','D','F','G','H','J','K','L'] as key}
            <div class="key">{key}</div>
          {/each}
        </div>
        <div class="keyboard-row">
          <div class="key key-shift">Shift</div>
          {#each ['Z','X','C','V','B','N','M'] as key}
            <div class="key">{key}</div>
          {/each}
        </div>
        <div class="keyboard-row">
          <div class="key key-ctrl">Ctrl</div>
          <div class="key key-alt">Alt</div>
          <div class="key key-space">Space</div>
          <div class="key key-sym">Sym</div>
        </div>
        <div class="candidate-bar">
          <span class="candidate active">你好</span>
          <span class="candidate">尼豪</span>
          <span class="candidate">泥号</span>
        </div>
      </div>
    </div>
  </section>

  <!-- Problem Section -->
  <section class="problem" data-section="problem" class:visible={visible.problem}>
    <div class="container">
      <h2>传统输入法的<span class="highlight">痛点</span></h2>
      <p class="section-desc">主流输入法为触屏设计，实体键盘用户被迫忍受这些问题：</p>
      <div class="problem-grid">
        <div class="problem-card">
          <div class="problem-icon">👆</div>
          <h3>频繁触屏</h3>
          <p>选词需要离开键盘点击屏幕，打断输入节奏</p>
        </div>
        <div class="problem-card">
          <div class="problem-icon">🔢</div>
          <h3>按键繁琐</h3>
          <p>全拼输入按键多，实体键盘打字疲劳</p>
        </div>
        <div class="problem-card">
          <div class="problem-icon">📄</div>
          <h3>反复翻页</h3>
          <p>候选词太多，需要反复翻页寻找</p>
        </div>
        <div class="problem-card">
          <div class="problem-icon">🧠</div>
          <h3>思维中断</h3>
          <p>操作冗余导致思维流被不断打断</p>
        </div>
      </div>
    </div>
  </section>

  <!-- Features Section -->
  <section id="features" class="features" data-section="features" class:visible={visible.features}>
    <div class="container">
      <h2>四大<span class="highlight">核心功能</span></h2>
      <p class="section-desc">从底层重构，专为实体键盘优化的输入体验</p>
      <div class="features-grid">
        {#each features as feature, i}
          <div class="feature-card" style="--delay: {i * 0.1}s">
            <div class="feature-icon">{feature.icon}</div>
            <h3>{feature.title}</h3>
            <p>{feature.desc}</p>
            <div class="feature-example">
              <code>{feature.example}</code>
            </div>
          </div>
        {/each}
      </div>
    </div>
  </section>

  <!-- Juying Mode Section -->
  <section id="juying" class="juying" data-section="juying" class:visible={visible.juying}>
    <div class="container">
      <div class="juying-content">
        <div class="juying-text">
          <div class="badge badge-purple">核心特色</div>
          <h2><span class="highlight">巨硬模式</span></h2>
          <p class="juying-desc">
            用 Modifier 键直接选词，彻底摆脱触屏与组合键。
            这是实体键盘输入效率的终极解决方案。
          </p>
          <div class="juying-keys">
            <div class="juying-key">
              <span class="key-label">Shift</span>
              <span class="key-action">选择候选1</span>
            </div>
            <div class="juying-key">
              <span class="key-label">Sym</span>
              <span class="key-action">选择候选2</span>
            </div>
            <div class="juying-key">
              <span class="key-label">Space</span>
              <span class="key-action">选择候选3</span>
            </div>
            <div class="juying-key">
              <span class="key-label">Ctrl</span>
              <span class="key-action">选择候选4</span>
            </div>
            <div class="juying-key">
              <span class="key-label">Alt</span>
              <span class="key-action">选择候选5</span>
            </div>
          </div>
          <div class="juying-benefits">
            <div class="benefit">✓ 无需触摸屏幕</div>
            <div class="benefit">✓ 无需数字键</div>
            <div class="benefit">✓ 一步完成选词</div>
            <div class="benefit">✓ 手不离开键盘</div>
          </div>
        </div>
        <div class="juying-visual">
          <div class="juying-demo">
            <div class="demo-input">nhwmjtkh</div>
            <div class="demo-candidates">
              <div class="demo-candidate">
                <span class="demo-key">Shift</span>
                <span class="demo-word">你好我们今天开会</span>
              </div>
              <div class="demo-candidate">
                <span class="demo-key">Sym</span>
                <span class="demo-word">你好我们今天看货</span>
              </div>
              <div class="demo-candidate">
                <span class="demo-key">Space</span>
                <span class="demo-word">您好我们今天开会</span>
              </div>
            </div>
            <div class="demo-result">
              <span class="demo-label">按下 Shift</span>
              <span class="demo-output">你好我们今天开会</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- Voice Recognition Section -->
  <section id="voice" class="voice" data-section="voice" class:visible={visible.voice}>
    <div class="container">
      <div class="voice-content">
        <div class="voice-visual">
          <div class="voice-circle">
            <div class="voice-wave"></div>
            <div class="voice-wave"></div>
            <div class="voice-wave"></div>
            <div class="voice-icon">🎙️</div>
          </div>
          <div class="voice-text-demo">
            <span class="voice-recognized">今天天气真不错</span>
          </div>
        </div>
        <div class="voice-text">
          <div class="badge badge-green">离线运行</div>
          <h2>本地<span class="highlight">语音识别</span></h2>
          <p class="voice-desc">
            采用 Sherpa-ONNX 深度学习引擎，实现高精度普通话语音识别。
            完全在本地运行，无需网络连接，保护您的隐私安全。
          </p>
          <div class="voice-features">
            <div class="voice-feature">
              <span class="vf-icon">🎯</span>
              <div>
                <h4>高精度识别</h4>
                <p>深度学习模型，准确识别普通话</p>
              </div>
            </div>
            <div class="voice-feature">
              <span class="vf-icon">📴</span>
              <div>
                <h4>完全离线</h4>
                <p>无需网络，随时随地使用</p>
              </div>
            </div>
            <div class="voice-feature">
              <span class="vf-icon">🔒</span>
              <div>
                <h4>隐私安全</h4>
                <p>语音数据不上传，安全无忧</p>
              </div>
            </div>
            <div class="voice-feature">
              <span class="vf-icon">⚡</span>
              <div>
                <h4>静音自动发送</h4>
                <p>说完自动输入，无需点击按钮</p>
              </div>
            </div>
          </div>
          <div class="voice-download">
            <a href="/sherpa-onnx-paraformer-zh-small-2024-03-09.zip" class="btn btn-voice-model" download>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="20" height="20">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/>
              </svg>
              <span>下载语音模型 (72MB)</span>
            </a>
            <p class="voice-download-note">需要单独下载语音模型以启用离线语音识别功能</p>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- Learning Section -->
  <section class="learning" data-section="learning" class:visible={visible.learning}>
    <div class="container">
      <h2>越用越<span class="highlight">懂你</span></h2>
      <p class="section-desc">本地深度自学习系统，打造专属于你的输入法</p>
      <div class="learning-content">
        <div class="learning-timeline">
          <div class="timeline-item">
            <div class="timeline-icon">📝</div>
            <div class="timeline-content">
              <h4>第一周</h4>
              <p>学习你的常用词汇和输入习惯</p>
            </div>
          </div>
          <div class="timeline-item">
            <div class="timeline-icon">📊</div>
            <div class="timeline-content">
              <h4>第二周</h4>
              <p>优化候选词排序，减少翻页</p>
            </div>
          </div>
          <div class="timeline-item">
            <div class="timeline-icon">🎯</div>
            <div class="timeline-content">
              <h4>一个月后</h4>
              <p>首字母直接命中，几乎不需选词</p>
            </div>
          </div>
          <div class="timeline-item">
            <div class="timeline-icon">🚀</div>
            <div class="timeline-content">
              <h4>长期使用</h4>
              <p>输入进入"无感化"状态</p>
            </div>
          </div>
        </div>
        <div class="learning-features">
          <div class="lf-item">
            <span>✓</span> 自动学习常用词和片语
          </div>
          <div class="lf-item">
            <span>✓</span> 优化个人候选优先级
          </div>
          <div class="lf-item">
            <span>✓</span> 支持自定义词库
          </div>
          <div class="lf-item">
            <span>✓</span> 所有学习在本地完成
          </div>
          <div class="lf-item">
            <span>✓</span> 数据永远属于你
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- Comparison Section -->
  <section class="comparison" data-section="comparison" class:visible={visible.comparison}>
    <div class="container">
      <h2>与主流输入法<span class="highlight">对比</span></h2>
      <p class="section-desc">专为实体键盘设计，本质性的不同</p>
      <div class="comparison-table">
        <div class="table-header">
          <div class="th-item">对比项</div>
          <div class="th-mainstream">主流输入法</div>
          <div class="th-coolwulf">酷狼输入法</div>
        </div>
        {#each comparisons as row}
          <div class="table-row">
            <div class="td-item">{row.item}</div>
            <div class="td-mainstream">{row.mainstream}</div>
            <div class="td-coolwulf">{row.coolwulf}</div>
          </div>
        {/each}
      </div>
    </div>
  </section>

  <!-- Free Section -->
  <section class="free" data-section="free" class:visible={visible.free}>
    <div class="container">
      <div class="free-content">
        <h2>完全<span class="highlight">免费</span></h2>
        <p class="free-desc">这不是商业策略，而是产品态度</p>
        <div class="free-grid">
          <div class="free-item">
            <div class="free-icon">💰</div>
            <h4>永久免费</h4>
            <p>所有功能完全开放</p>
          </div>
          <div class="free-item">
            <div class="free-icon">🚫</div>
            <h4>无广告</h4>
            <p>纯净的输入体验</p>
          </div>
          <div class="free-item">
            <div class="free-icon">🔐</div>
            <h4>不卖数据</h4>
            <p>输入数据永不外泄</p>
          </div>
          <div class="free-item">
            <div class="free-icon">☁️</div>
            <h4>不强制云同步</h4>
            <p>数据完全由你掌控</p>
          </div>
        </div>
        <p class="free-quote">
          "一个真正为输入效率服务，而不是为流量变现服务的工具"
        </p>
      </div>
    </div>
  </section>

  <!-- CTA Section -->
  <section id="download" class="cta" data-section="cta" class:visible={visible.cta}>
    <div class="container">
      <div class="cta-content">
        <h2>准备好提升你的<span class="highlight">输入效率</span>了吗？</h2>
        <p>
          如果你是全键盘手机用户、蓝牙键盘用户、或追求极致效率的人，<br />
          酷狼输入法就是为你量身定做的输入工具。
        </p>
        <div class="cta-buttons">
          <a href="/coolwulfIMEv0.80.1593.apk" class="btn btn-primary btn-large" download>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="24" height="24">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/>
            </svg>
            <span>下载 APK (v0.80)</span>
          </a>
          <a href="/sherpa-onnx-paraformer-zh-small-2024-03-09.zip" class="btn btn-voice-model btn-large" download>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="24" height="24">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/>
            </svg>
            <span>下载语音模型 (72MB)</span>
          </a>
        </div>
        <p class="cta-note">支持 Android 10+ · 需要实体键盘或蓝牙键盘 · APK约18MB</p>
      </div>
    </div>
  </section>

  <!-- Footer -->
  <footer>
    <div class="container">
      <div class="footer-content">
        <div class="footer-brand">
          <span class="logo-icon">🐺</span>
          <span>酷狼输入法</span>
        </div>
        <p>为实体键盘效率而生 · 永久免费 · 开源项目</p>
        <div class="footer-links">
          <a href="https://github.com/user/coolwulf-ime">GitHub</a>
          <span>·</span>
          <a href="mailto:contact@coolwulf.com">联系我们</a>
        </div>
      </div>
    </div>
  </footer>
</main>

<style>
  :global(*) {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
  }

  :global(html) {
    scroll-behavior: smooth;
  }

  :global(body) {
    font-family: 'Noto Sans SC', -apple-system, BlinkMacSystemFont, sans-serif;
    background: #0a0a0f;
    color: #e0e0e0;
    line-height: 1.6;
    overflow-x: hidden;
  }

  main {
    min-height: 100vh;
  }

  .container {
    max-width: 1200px;
    margin: 0 auto;
    padding: 0 24px;
  }

  h2 {
    font-size: clamp(2rem, 5vw, 3rem);
    font-weight: 700;
    margin-bottom: 16px;
    text-align: center;
  }

  .highlight {
    background: linear-gradient(135deg, #6366f1, #8b5cf6);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    background-clip: text;
  }

  .section-desc {
    text-align: center;
    color: #888;
    font-size: 1.1rem;
    margin-bottom: 48px;
    max-width: 600px;
    margin-left: auto;
    margin-right: auto;
  }

  .badge {
    display: inline-block;
    padding: 8px 16px;
    background: rgba(99, 102, 241, 0.15);
    border: 1px solid rgba(99, 102, 241, 0.3);
    border-radius: 100px;
    font-size: 0.85rem;
    color: #a5b4fc;
    margin-bottom: 16px;
  }

  .badge-purple {
    background: rgba(139, 92, 246, 0.15);
    border-color: rgba(139, 92, 246, 0.3);
    color: #c4b5fd;
  }

  .badge-green {
    background: rgba(34, 197, 94, 0.15);
    border-color: rgba(34, 197, 94, 0.3);
    color: #86efac;
  }

  .btn {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 14px 28px;
    border-radius: 12px;
    font-size: 1rem;
    font-weight: 500;
    text-decoration: none;
    transition: all 0.3s ease;
    cursor: pointer;
    border: none;
  }

  .btn svg {
    width: 20px;
    height: 20px;
  }

  .btn-primary {
    background: linear-gradient(135deg, #6366f1, #8b5cf6);
    color: white;
    box-shadow: 0 4px 20px rgba(99, 102, 241, 0.4);
  }

  .btn-primary:hover {
    transform: translateY(-2px);
    box-shadow: 0 6px 30px rgba(99, 102, 241, 0.5);
  }

  .btn-secondary {
    background: rgba(255, 255, 255, 0.05);
    color: #e0e0e0;
    border: 1px solid rgba(255, 255, 255, 0.1);
  }

  .btn-secondary:hover {
    background: rgba(255, 255, 255, 0.1);
  }

  .btn-large {
    padding: 18px 36px;
    font-size: 1.1rem;
  }

  /* Navigation */
  nav {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    z-index: 1000;
    padding: 20px 0;
    transition: all 0.3s ease;
  }

  nav.scrolled {
    background: rgba(10, 10, 15, 0.9);
    backdrop-filter: blur(20px);
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
    padding: 12px 0;
  }

  .nav-content {
    max-width: 1200px;
    margin: 0 auto;
    padding: 0 24px;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .logo {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 1.25rem;
    font-weight: 700;
  }

  .logo-icon {
    font-size: 1.5rem;
  }

  .nav-links {
    display: flex;
    align-items: center;
    gap: 32px;
  }

  .nav-links a {
    color: #888;
    text-decoration: none;
    font-size: 0.95rem;
    transition: color 0.3s ease;
  }

  .nav-links a:hover {
    color: #fff;
  }

  .btn-nav {
    padding: 10px 20px;
    background: rgba(99, 102, 241, 0.2);
    border-radius: 8px;
    color: #a5b4fc !important;
  }

  .btn-nav:hover {
    background: rgba(99, 102, 241, 0.3);
  }

  /* Hero Section */
  .hero {
    min-height: 100vh;
    display: flex;
    align-items: center;
    padding: 100px 24px;
    position: relative;
    overflow: hidden;
    opacity: 0;
    transform: translateY(30px);
    transition: all 0.8s ease;
  }

  .hero.visible {
    opacity: 1;
    transform: translateY(0);
  }

  .hero-bg {
    position: absolute;
    inset: 0;
    overflow: hidden;
  }

  .grid-overlay {
    position: absolute;
    inset: 0;
    background-image:
      linear-gradient(rgba(99, 102, 241, 0.03) 1px, transparent 1px),
      linear-gradient(90deg, rgba(99, 102, 241, 0.03) 1px, transparent 1px);
    background-size: 60px 60px;
  }

  .glow {
    position: absolute;
    border-radius: 50%;
    filter: blur(100px);
    opacity: 0.5;
  }

  .glow-1 {
    width: 600px;
    height: 600px;
    background: rgba(99, 102, 241, 0.3);
    top: -200px;
    right: -100px;
  }

  .glow-2 {
    width: 400px;
    height: 400px;
    background: rgba(139, 92, 246, 0.3);
    bottom: -100px;
    left: -100px;
  }

  .hero-content {
    position: relative;
    z-index: 1;
    max-width: 600px;
  }

  .hero h1 {
    font-size: clamp(2.5rem, 6vw, 4rem);
    font-weight: 900;
    line-height: 1.2;
    margin-bottom: 24px;
  }

  .gradient-text {
    background: linear-gradient(135deg, #fff, #a5b4fc);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    background-clip: text;
  }

  .subtitle {
    font-size: 0.5em;
    font-weight: 400;
    color: #888;
  }

  .hero-desc {
    font-size: 1.1rem;
    color: #aaa;
    margin-bottom: 32px;
    line-height: 1.8;
  }

  .hero-desc strong {
    color: #22c55e;
  }

  .hero-buttons {
    display: flex;
    gap: 16px;
    margin-bottom: 48px;
    flex-wrap: wrap;
  }

  .hero-stats {
    display: flex;
    gap: 48px;
  }

  .stat {
    display: flex;
    flex-direction: column;
  }

  .stat-value {
    font-size: 2rem;
    font-weight: 700;
    color: #fff;
  }

  .stat-label {
    font-size: 0.85rem;
    color: #666;
  }

  .hero-visual {
    position: absolute;
    right: 5%;
    top: 50%;
    transform: translateY(-50%);
    z-index: 1;
  }

  .keyboard-mockup {
    background: rgba(20, 20, 30, 0.8);
    border: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 16px;
    padding: 20px;
    backdrop-filter: blur(10px);
  }

  .keyboard-row {
    display: flex;
    gap: 6px;
    margin-bottom: 6px;
    justify-content: center;
  }

  .key {
    width: 36px;
    height: 36px;
    background: rgba(255, 255, 255, 0.05);
    border: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 6px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 0.75rem;
    color: #888;
    transition: all 0.2s ease;
  }

  .key:hover {
    background: rgba(99, 102, 241, 0.2);
    border-color: rgba(99, 102, 241, 0.4);
    color: #fff;
  }

  .key-shift, .key-ctrl, .key-alt, .key-sym {
    width: auto;
    padding: 0 12px;
    font-size: 0.65rem;
    background: rgba(99, 102, 241, 0.15);
    border-color: rgba(99, 102, 241, 0.3);
    color: #a5b4fc;
  }

  .key-space {
    width: 120px;
    background: rgba(99, 102, 241, 0.15);
    border-color: rgba(99, 102, 241, 0.3);
    color: #a5b4fc;
    font-size: 0.65rem;
  }

  .candidate-bar {
    margin-top: 12px;
    padding: 10px;
    background: rgba(0, 0, 0, 0.3);
    border-radius: 8px;
    display: flex;
    gap: 16px;
    justify-content: center;
  }

  .candidate {
    padding: 4px 12px;
    border-radius: 4px;
    font-size: 0.9rem;
    color: #888;
  }

  .candidate.active {
    background: linear-gradient(135deg, #6366f1, #8b5cf6);
    color: #fff;
  }

  /* Problem Section */
  .problem {
    padding: 120px 24px;
    background: linear-gradient(180deg, #0a0a0f 0%, #12121a 100%);
    opacity: 0;
    transform: translateY(30px);
    transition: all 0.8s ease;
  }

  .problem.visible {
    opacity: 1;
    transform: translateY(0);
  }

  .problem-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
    gap: 24px;
  }

  .problem-card {
    background: rgba(255, 255, 255, 0.02);
    border: 1px solid rgba(255, 255, 255, 0.05);
    border-radius: 16px;
    padding: 32px;
    text-align: center;
    transition: all 0.3s ease;
  }

  .problem-card:hover {
    background: rgba(255, 255, 255, 0.04);
    border-color: rgba(239, 68, 68, 0.3);
    transform: translateY(-4px);
  }

  .problem-icon {
    font-size: 2.5rem;
    margin-bottom: 16px;
  }

  .problem-card h3 {
    font-size: 1.25rem;
    margin-bottom: 8px;
    color: #ef4444;
  }

  .problem-card p {
    color: #888;
    font-size: 0.95rem;
  }

  /* Features Section */
  .features {
    padding: 120px 24px;
    opacity: 0;
    transform: translateY(30px);
    transition: all 0.8s ease;
  }

  .features.visible {
    opacity: 1;
    transform: translateY(0);
  }

  .features-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    gap: 24px;
  }

  .feature-card {
    background: linear-gradient(135deg, rgba(99, 102, 241, 0.1), rgba(139, 92, 246, 0.05));
    border: 1px solid rgba(99, 102, 241, 0.2);
    border-radius: 20px;
    padding: 32px;
    transition: all 0.3s ease;
    opacity: 0;
    transform: translateY(20px);
    animation: fadeInUp 0.6s ease forwards;
    animation-delay: var(--delay);
  }

  .features.visible .feature-card {
    opacity: 1;
    transform: translateY(0);
  }

  @keyframes fadeInUp {
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }

  .feature-card:hover {
    transform: translateY(-8px);
    box-shadow: 0 20px 40px rgba(99, 102, 241, 0.2);
    border-color: rgba(99, 102, 241, 0.4);
  }

  .feature-icon {
    font-size: 3rem;
    margin-bottom: 16px;
  }

  .feature-card h3 {
    font-size: 1.35rem;
    margin-bottom: 12px;
    color: #fff;
  }

  .feature-card p {
    color: #aaa;
    margin-bottom: 16px;
  }

  .feature-example {
    background: rgba(0, 0, 0, 0.3);
    border-radius: 8px;
    padding: 12px;
  }

  .feature-example code {
    color: #a5b4fc;
    font-size: 0.85rem;
    font-family: 'SF Mono', monospace;
  }

  /* Juying Section */
  .juying {
    padding: 120px 24px;
    background: linear-gradient(180deg, #0a0a0f 0%, #0f0f18 50%, #0a0a0f 100%);
    opacity: 0;
    transform: translateY(30px);
    transition: all 0.8s ease;
  }

  .juying.visible {
    opacity: 1;
    transform: translateY(0);
  }

  .juying-content {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 64px;
    align-items: center;
  }

  .juying-text h2 {
    text-align: left;
    margin-bottom: 24px;
  }

  .juying-desc {
    font-size: 1.1rem;
    color: #aaa;
    margin-bottom: 32px;
    line-height: 1.8;
  }

  .juying-keys {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    margin-bottom: 32px;
  }

  .juying-key {
    display: flex;
    flex-direction: column;
    align-items: center;
    background: rgba(139, 92, 246, 0.1);
    border: 1px solid rgba(139, 92, 246, 0.3);
    border-radius: 12px;
    padding: 16px 20px;
    min-width: 80px;
  }

  .key-label {
    font-size: 0.85rem;
    font-weight: 600;
    color: #c4b5fd;
    margin-bottom: 4px;
  }

  .key-action {
    font-size: 0.75rem;
    color: #888;
  }

  .juying-benefits {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
  }

  .benefit {
    color: #22c55e;
    font-size: 0.95rem;
  }

  .juying-visual {
    display: flex;
    justify-content: center;
  }

  .juying-demo {
    background: rgba(20, 20, 30, 0.8);
    border: 1px solid rgba(139, 92, 246, 0.3);
    border-radius: 20px;
    padding: 32px;
    width: 100%;
    max-width: 400px;
  }

  .demo-input {
    background: rgba(0, 0, 0, 0.4);
    border-radius: 8px;
    padding: 16px;
    font-family: 'SF Mono', monospace;
    font-size: 1.5rem;
    color: #a5b4fc;
    text-align: center;
    margin-bottom: 24px;
    letter-spacing: 2px;
  }

  .demo-candidates {
    display: flex;
    flex-direction: column;
    gap: 12px;
    margin-bottom: 24px;
  }

  .demo-candidate {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 12px;
    background: rgba(255, 255, 255, 0.02);
    border-radius: 8px;
  }

  .demo-key {
    background: rgba(139, 92, 246, 0.2);
    border: 1px solid rgba(139, 92, 246, 0.4);
    border-radius: 6px;
    padding: 6px 12px;
    font-size: 0.75rem;
    color: #c4b5fd;
    min-width: 60px;
    text-align: center;
  }

  .demo-word {
    color: #e0e0e0;
  }

  .demo-result {
    background: linear-gradient(135deg, rgba(34, 197, 94, 0.1), rgba(34, 197, 94, 0.05));
    border: 1px solid rgba(34, 197, 94, 0.3);
    border-radius: 12px;
    padding: 16px;
    text-align: center;
  }

  .demo-label {
    display: block;
    font-size: 0.8rem;
    color: #86efac;
    margin-bottom: 8px;
  }

  .demo-output {
    font-size: 1.25rem;
    color: #fff;
  }

  /* Voice Section */
  .voice {
    padding: 120px 24px;
    opacity: 0;
    transform: translateY(30px);
    transition: all 0.8s ease;
  }

  .voice.visible {
    opacity: 1;
    transform: translateY(0);
  }

  .voice-content {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 64px;
    align-items: center;
  }

  .voice-visual {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 32px;
  }

  .voice-circle {
    position: relative;
    width: 200px;
    height: 200px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .voice-wave {
    position: absolute;
    border: 2px solid rgba(34, 197, 94, 0.3);
    border-radius: 50%;
    animation: pulse 2s ease-out infinite;
  }

  .voice-wave:nth-child(1) {
    width: 100%;
    height: 100%;
    animation-delay: 0s;
  }

  .voice-wave:nth-child(2) {
    width: 75%;
    height: 75%;
    animation-delay: 0.3s;
  }

  .voice-wave:nth-child(3) {
    width: 50%;
    height: 50%;
    animation-delay: 0.6s;
  }

  @keyframes pulse {
    0% {
      transform: scale(1);
      opacity: 1;
    }
    100% {
      transform: scale(1.5);
      opacity: 0;
    }
  }

  .voice-icon {
    font-size: 4rem;
    z-index: 1;
  }

  .voice-text-demo {
    background: rgba(34, 197, 94, 0.1);
    border: 1px solid rgba(34, 197, 94, 0.3);
    border-radius: 12px;
    padding: 16px 32px;
  }

  .voice-recognized {
    font-size: 1.25rem;
    color: #86efac;
  }

  .voice-text h2 {
    text-align: left;
    margin-bottom: 24px;
  }

  .voice-desc {
    font-size: 1.1rem;
    color: #aaa;
    margin-bottom: 32px;
    line-height: 1.8;
  }

  .voice-features {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 24px;
  }

  .voice-feature {
    display: flex;
    gap: 16px;
    align-items: flex-start;
  }

  .vf-icon {
    font-size: 1.5rem;
    flex-shrink: 0;
  }

  .voice-feature h4 {
    font-size: 1rem;
    color: #fff;
    margin-bottom: 4px;
  }

  .voice-feature p {
    font-size: 0.85rem;
    color: #888;
  }

  .voice-download {
    margin-top: 32px;
    padding-top: 24px;
    border-top: 1px solid rgba(255, 255, 255, 0.1);
  }

  .btn-voice-model {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    padding: 14px 24px;
    background: rgba(34, 197, 94, 0.15);
    border: 1px solid rgba(34, 197, 94, 0.4);
    border-radius: 12px;
    color: #86efac;
    text-decoration: none;
    font-weight: 500;
    transition: all 0.3s ease;
  }

  .btn-voice-model:hover {
    background: rgba(34, 197, 94, 0.25);
    transform: translateY(-2px);
    box-shadow: 0 4px 20px rgba(34, 197, 94, 0.3);
  }

  .voice-download-note {
    margin-top: 12px;
    font-size: 0.85rem;
    color: #666;
  }

  /* Learning Section */
  .learning {
    padding: 120px 24px;
    background: linear-gradient(180deg, #0a0a0f 0%, #12121a 100%);
    opacity: 0;
    transform: translateY(30px);
    transition: all 0.8s ease;
  }

  .learning.visible {
    opacity: 1;
    transform: translateY(0);
  }

  .learning-content {
    display: grid;
    grid-template-columns: 2fr 1fr;
    gap: 64px;
    align-items: start;
  }

  .learning-timeline {
    position: relative;
    padding-left: 40px;
  }

  .learning-timeline::before {
    content: '';
    position: absolute;
    left: 12px;
    top: 0;
    bottom: 0;
    width: 2px;
    background: linear-gradient(180deg, #6366f1, #8b5cf6);
  }

  .timeline-item {
    display: flex;
    gap: 24px;
    margin-bottom: 40px;
    position: relative;
  }

  .timeline-icon {
    position: absolute;
    left: -40px;
    width: 28px;
    height: 28px;
    background: #0a0a0f;
    border: 2px solid #6366f1;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 0.9rem;
  }

  .timeline-content h4 {
    font-size: 1.1rem;
    color: #fff;
    margin-bottom: 8px;
  }

  .timeline-content p {
    color: #888;
  }

  .learning-features {
    background: rgba(99, 102, 241, 0.05);
    border: 1px solid rgba(99, 102, 241, 0.2);
    border-radius: 16px;
    padding: 32px;
  }

  .lf-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 0;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
    color: #aaa;
  }

  .lf-item:last-child {
    border-bottom: none;
  }

  .lf-item span {
    color: #22c55e;
    font-weight: bold;
  }

  /* Comparison Section */
  .comparison {
    padding: 120px 24px;
    opacity: 0;
    transform: translateY(30px);
    transition: all 0.8s ease;
  }

  .comparison.visible {
    opacity: 1;
    transform: translateY(0);
  }

  .comparison-table {
    background: rgba(255, 255, 255, 0.02);
    border: 1px solid rgba(255, 255, 255, 0.05);
    border-radius: 20px;
    overflow: hidden;
    max-width: 800px;
    margin: 0 auto;
  }

  .table-header {
    display: grid;
    grid-template-columns: 1.5fr 1fr 1fr;
    background: rgba(99, 102, 241, 0.1);
    border-bottom: 1px solid rgba(99, 102, 241, 0.2);
  }

  .table-header > div {
    padding: 20px;
    font-weight: 600;
    text-align: center;
  }

  .th-item {
    text-align: left !important;
    color: #888;
  }

  .th-mainstream {
    color: #888;
  }

  .th-coolwulf {
    color: #a5b4fc;
  }

  .table-row {
    display: grid;
    grid-template-columns: 1.5fr 1fr 1fr;
    border-bottom: 1px solid rgba(255, 255, 255, 0.03);
    transition: background 0.3s ease;
  }

  .table-row:hover {
    background: rgba(255, 255, 255, 0.02);
  }

  .table-row:last-child {
    border-bottom: none;
  }

  .table-row > div {
    padding: 16px 20px;
    text-align: center;
  }

  .td-item {
    text-align: left !important;
    color: #e0e0e0;
  }

  .td-mainstream {
    color: #ef4444;
  }

  .td-coolwulf {
    color: #22c55e;
    font-weight: 500;
  }

  /* Free Section */
  .free {
    padding: 120px 24px;
    background: linear-gradient(180deg, #0a0a0f 0%, #0f0f18 100%);
    opacity: 0;
    transform: translateY(30px);
    transition: all 0.8s ease;
  }

  .free.visible {
    opacity: 1;
    transform: translateY(0);
  }

  .free-content {
    text-align: center;
  }

  .free-desc {
    font-size: 1.2rem;
    color: #888;
    margin-bottom: 48px;
  }

  .free-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 24px;
    max-width: 900px;
    margin: 0 auto 48px;
  }

  .free-item {
    background: rgba(34, 197, 94, 0.05);
    border: 1px solid rgba(34, 197, 94, 0.2);
    border-radius: 16px;
    padding: 32px;
    transition: all 0.3s ease;
  }

  .free-item:hover {
    transform: translateY(-4px);
    border-color: rgba(34, 197, 94, 0.4);
  }

  .free-icon {
    font-size: 2.5rem;
    margin-bottom: 16px;
  }

  .free-item h4 {
    font-size: 1.1rem;
    color: #22c55e;
    margin-bottom: 8px;
  }

  .free-item p {
    color: #888;
    font-size: 0.9rem;
  }

  .free-quote {
    font-size: 1.1rem;
    color: #aaa;
    font-style: italic;
    max-width: 600px;
    margin: 0 auto;
    padding: 24px;
    border-left: 3px solid #22c55e;
    text-align: left;
    background: rgba(34, 197, 94, 0.05);
    border-radius: 0 12px 12px 0;
  }

  /* CTA Section */
  .cta {
    padding: 120px 24px;
    background: linear-gradient(135deg, rgba(99, 102, 241, 0.1), rgba(139, 92, 246, 0.05));
    border-top: 1px solid rgba(99, 102, 241, 0.2);
    opacity: 0;
    transform: translateY(30px);
    transition: all 0.8s ease;
  }

  .cta.visible {
    opacity: 1;
    transform: translateY(0);
  }

  .cta-content {
    text-align: center;
    max-width: 700px;
    margin: 0 auto;
  }

  .cta-content h2 {
    margin-bottom: 24px;
  }

  .cta-content > p {
    font-size: 1.1rem;
    color: #aaa;
    margin-bottom: 40px;
    line-height: 1.8;
  }

  .cta-buttons {
    display: flex;
    justify-content: center;
    gap: 16px;
    margin-bottom: 24px;
  }

  .cta-note {
    font-size: 0.85rem;
    color: #666;
  }

  /* Footer */
  footer {
    padding: 48px 24px;
    border-top: 1px solid rgba(255, 255, 255, 0.05);
  }

  .footer-content {
    text-align: center;
  }

  .footer-brand {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-size: 1.25rem;
    font-weight: 600;
    margin-bottom: 12px;
  }

  .footer-content > p {
    color: #666;
    font-size: 0.9rem;
    margin-bottom: 16px;
  }

  .footer-links {
    display: flex;
    justify-content: center;
    gap: 16px;
    color: #666;
  }

  .footer-links a {
    color: #888;
    text-decoration: none;
    transition: color 0.3s ease;
  }

  .footer-links a:hover {
    color: #a5b4fc;
  }

  /* Responsive */
  @media (max-width: 1024px) {
    .hero-visual {
      display: none;
    }

    .juying-content,
    .voice-content,
    .learning-content {
      grid-template-columns: 1fr;
    }

    .voice-visual {
      order: -1;
    }

    .juying-visual {
      order: -1;
    }
  }

  @media (max-width: 768px) {
    .nav-links {
      display: none;
    }

    .hero-stats {
      flex-direction: column;
      gap: 24px;
    }

    .hero-buttons {
      flex-direction: column;
    }

    .btn {
      width: 100%;
      justify-content: center;
    }

    .juying-benefits {
      grid-template-columns: 1fr;
    }

    .voice-features {
      grid-template-columns: 1fr;
    }

    .learning-content {
      gap: 32px;
    }

    .comparison-table {
      font-size: 0.85rem;
    }

    .table-header > div,
    .table-row > div {
      padding: 12px 8px;
    }
  }
</style>
