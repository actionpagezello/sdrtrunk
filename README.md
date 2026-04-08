<p>
  <img src="https://github.com/madmac85/sdrtrunk-actionpage-vsam/actions/workflows/gradle.yml/badge.svg" alt="Gradle Build">
  <img src="https://github.com/madmac85/sdrtrunk-actionpage-vsam/actions/workflows/nightly.yml/badge.svg" alt="Nightly Release">
</p>

<h1>sdrtrunk (ActionPage &amp; vSam Fork)</h1>

<p>A cross-platform java application for decoding, monitoring, recording and streaming trunked mobile and related radio protocols using Software Defined Radios (SDR).</p>

<p><em>This is a customized fork of the original dsheirer/sdrtrunk repository, specifically modified to include ActionPage integration and vSam enhancements.</em></p>

<h2>New Features &amp; Fixes in this Fork</h2>
<ul>
  <li><strong>ActionPage Integration:</strong> Custom features supporting ActionPage workflows.</li>
  <li><strong>vSam Enhancements:</strong> Specialized modifications for vSam compatibility.</li>
  <li><strong>MacOS Tahoe 26.1 USB Fix:</strong> Includes updated usb4java native libraries compiled for MacOS Tahoe (ARM processors) to resolve launch failures caused by USB support changes in Tahoe 26.x.</li>
</ul>

<h2>MacOS Tahoe 26.1 Users - Attention:</h2>
<p>Changes to USB support in Tahoe version 26.x cause standard sdrtrunk builds to fail to launch. Do the following to install the latest libusb and create a symbolic link, and then use the nightly build which includes the updated usb4java native library for Tahoe with ARM processor. There may still be issue(s) with MacOS accessing your USB SDR tuners.</p>

<pre><code class="language-bash">brew install libusb --HEAD
cd /opt
sudo mkdir local
cd local
sudo mkdir lib
</code></pre>

<p>Next, find where brew installed the libusb library, for example: <code>/opt/homebrew/Cellar/libusb/HEAD-9ceaa52/lib/libusb-1.0.0.dylib</code><br>
<em>(Note: the folder "HEAD-9ceaa52" is the version stamp for HEAD when you installed from it.)</em></p>

<p>Finally, create a symbolic link from the installed library to the place where usb4java is expecting to find libusb (<code>/opt/local/lib/libusb-1.0.0.dylib</code>)</p>

<pre><code class="language-bash">sudo ln -s /opt/homebrew/Cellar/libusb/HEAD-9ceaa52/lib/libusb-1.0.0.dylib /opt/local/lib/libusb-1.0.0.dylib
</code></pre>

<h2>Documentation &amp; Support</h2>
<ul>
  <li><a href="https://github.com/madmac85/sdrtrunk-actionpage-vsam/wiki">Help/Wiki Home Page</a></li>
  <li><a href="https://github.com/madmac85/sdrtrunk-actionpage-vsam/wiki/Getting-Started">Getting Started</a></li>
  <li><a href="https://github.com/madmac85/sdrtrunk-actionpage-vsam/wiki/User-Manual">User's Manual</a></li>
  <li><a href="https://github.com/madmac85/sdrtrunk-actionpage-vsam/wiki/Support">Support</a></li>
</ul>

<p>
  <img src="https://github.com/madmac85/sdrtrunk-actionpage-vsam/wiki/images/sdrtrunk.png" alt="sdrtrunk Application"><br>
  <em>Figure 1:</em> sdrtrunk Application Screenshot
</p>

<h2>Downloads</h2>
<p><em>Note: Original download links have been removed. Please use the links below for this fork's releases.</em></p>

<p>All release versions of this fork are available from the <a href="https://github.com/madmac85/sdrtrunk-actionpage-vsam/releases">Releases</a> tab.</p>

<ul>
  <li><em>(alpha)</em> These versions are under development feature previews and likely to contain bugs and unexpected behavior.</li>
  <li><em>(beta)</em> These versions are currently being tested for bugs and functionality prior to final release.</li>
  <li><em>(final)</em> These versions have been tested and are the current release version.</li>
</ul>

<h3>Download Nightly Software Build</h3>
<p>The <a href="https://github.com/madmac85/sdrtrunk-actionpage-vsam/releases/tag/nightly">nightly</a> release contains current builds of the software for all supported operating systems. This version of the software may contain bugs and may not run correctly. However, it lets you preview the most recent changes and fixes before the next software release.</p>

<p><em>Always backup your playlist(s) before you use the nightly builds.</em></p>

<p><em>Note: the nightly release is updated each time code changes are committed to the code base, so it's not really 'nightly' as much as it is 'current'.</em></p>

<h2>Minimum System Requirements</h2>
<ul>
  <li><strong>Operating System:</strong> Windows (64-bit), Linux (64-bit) or Mac (64-bit, 12.x or higher)</li>
  <li><strong>CPU:</strong> 4-core</li>
  <li><strong>RAM:</strong> 8GB or more (preferred). Depending on usage, 4GB may be sufficient.</li>
</ul>
