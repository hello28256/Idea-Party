#!/usr/bin/env bash
# 一次性脚本：从英文维基百科下载 36 位预设角色的真实肖像到本地
# 用途：让推荐区头像完全离线，避免每次请求都打外网（且国内访问维基偶尔超时）
# 重跑安全：已存在的同名文件会被跳过；失败的会重试 2 次
set -u
DEST="$(cd "$(dirname "$0")" && pwd)"  # 切到脚本所在目录 = uploads/avatars/presets/
echo "目标目录：$DEST"

# 角色名（中） -> 文件名（英文小写，连字符分隔，无音调）
# 用英文文件名避免 URL 编码、便于在文件系统/URL/数据库中稳定引用
declare -A NAMES=(
  [1]="confucius"  [2]="socrates"  [3]="laozi"   [4]="buddha"  [5]="jesus"
  [6]="muhammad"  [7]="galileo"   [8]="newton"  [9]="darwin"  [10]="einstein"
  [11]="marie-curie"  [12]="tesla"  [13]="shakespeare"  [14]="plato"  [15]="leonardo-da-vinci"
  [16]="genghis-khan" [17]="napoleon" [18]="mao-zedong" [19]="aristotle" [20]="karl-marx"
  [21]="lenin"   [22]="rousseau"  [23]="voltaire" [24]="kant"  [25]="hegel"
  [26]="nietzsche"   [27]="freud"  [28]="galois" [29]="gauss"  [30]="maxwell"
  [31]="bohr"   [32]="heisenberg" [33]="bach"  [34]="mozart" [35]="beethoven"
  [36]="vincent-van-gogh"
)

# 维基百科原始 URL（按 DataLoader.java 里的顺序）
URLS=(
  "https://upload.wikimedia.org/wikipedia/commons/thumb/5/54/Confucius_Tang_Dynasty.jpg/250px-Confucius_Tang_Dynasty.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a4/Socrates_Louvre.jpg/250px-Socrates_Louvre.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/a/af/Laozi_002.jpg/250px-Laozi_002.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d0/Standing_Buddha_Gandhara._Mus%C3%A9e_des_arts_asiatiques_Guimet.jpg/250px-Standing_Buddha_Gandhara._Mus%C3%A9e_des_arts_asiatiques_Guimet.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3b/Spas_vsederzhitel_sinay_%28cropped1%29.jpg/250px-Spas_vsederzhitel_sinay_%28cropped1%29.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/7/73/Al-Masjid_AL-Nabawi_Door.jpg/250px-Al-Masjid_AL-Nabawi_Door.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/f/fc/Galileo_Galilei_%281564-1642%29_RMG_BHC2700.tiff/lossy-page1-250px-Galileo_Galilei_%281564-1642%29_RMG_BHC2700.tiff.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f7/Portrait_of_Sir_Isaac_Newton%2C_1689_%28brightened%29.jpg/250px-Portrait_of_Sir_Isaac_Newton%2C_1689_%28brightened%29.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2e/Charles_Darwin_seated_crop.jpg/250px-Charles_Darwin_seated_crop.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/2/28/Albert_Einstein_Head_cleaned.jpg/250px-Albert_Einstein_Head_cleaned.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c8/Marie_Curie_c._1920s.jpg/250px-Marie_Curie_c._1920s.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/7/79/Tesla_circa_1890.jpeg/250px-Tesla_circa_1890.jpeg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/2/21/William_Shakespeare_by_John_Taylor%2C_edited.jpg/250px-William_Shakespeare_by_John_Taylor%2C_edited.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/2/21/Plato_Silanion_Musei_Capitolini_MC1377.png/250px-Plato_Silanion_Musei_Capitolini_MC1377.png"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/c/cb/Francesco_Melzi_-_Portrait_of_Leonardo.png/250px-Francesco_Melzi_-_Portrait_of_Leonardo.png"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/3/35/YuanEmperorAlbumGenghisPortrait.jpg/250px-YuanEmperorAlbumGenghisPortrait.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/5/50/Jacques-Louis_David_-_The_Emperor_Napoleon_in_His_Study_at_the_Tuileries_-_Google_Art_Project.jpg/250px-Jacques-Louis_David_-_The_Emperor_Napoleon_in_His_Study_at_the_Tuileries_-_Google_Art_Project.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5e/Mao_Zedong_1950_Portrait_%283x4_cropped%29%282%29.jpg/250px-Mao_Zedong_1950_Portrait_%283x4_cropped%29%282%29.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ae/Aristotle_Altemps_Inv8575.jpg/250px-Aristotle_Altemps_Inv8575.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f3/Karl_Marx_by_John_Jabez_Edwin_Mayall_1875_-_Restored_%26_Adjusted_%283x4_cropped_b%29.png/250px-Karl_Marx_by_John_Jabez_Edwin_Mayall_1875_-_Restored_%26_Adjusted_%283x4_cropped_b%29.png"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c0/Lenin_in_1920_%28cropped%29.jpg/250px-Lenin_in_1920_%28cropped%29.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4c/Maurice_Quentin_de_La_Tour_-_Portrait_of_Jean-Jacques_Rousseau_-_adjusted.jpg/250px-Maurice_Quentin_de_La_Tour_-_Portrait_of_Jean-Jacques_Rousseau_-_adjusted.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/b/bf/Nicolas_de_Largilli%C3%A8re_-_Portrait_de_Voltaire_%281694-1778%29_en_1718_-_P208_-_mus%C3%A9e_Carnavalet_-_5_%28cropped%29.jpg/250px-Nicolas_de_Largilli%C3%A8re_-_Portrait_de_Voltaire_%281694-1778%29_en_1718_-_P208_-_mus%C3%A9e_Carnavalet_-_5_%28cropped%29.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/7/79/Immanuel_Kant_-_Gemaelde_1.jpg/250px-Immanuel_Kant_-_Gemaelde_1.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/c/cc/Jakob_Schlesinger_-_Hegel_1831.jpg/250px-Jakob_Schlesinger_-_Hegel_1831.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1b/Nietzsche187a.jpg/250px-Nietzsche187a.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/3/36/Sigmund_Freud%2C_by_Max_Halberstadt_%28cropped%29.jpg/250px-Sigmund_Freud%2C_by_Max_Halberstadt_%28cropped%29.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/5/53/Evariste_galois.jpg/250px-Evariste_galois.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/e/ec/Carl_Friedrich_Gauss_1840_by_Jensen.jpg/250px-Carl_Friedrich_Gauss_1840_by_Jensen.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b0/James-Clerk-Maxwell-1831-1879.jpg/250px-James-Clerk-Maxwell-1831-1879.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6d/Niels_Bohr.jpg/250px-Niels_Bohr.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/e/ee/Werner_Heisenberg_Portrait_%283x4_cropped%29.jpg/250px-Werner_Heisenberg_Portrait_%283x4_cropped%29.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6a/Johann_Sebastian_Bach.jpg/250px-Johann_Sebastian_Bach.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ad/The_Mozart_Family_-_Wolfgang_Amadeus_Mozart_headshot.jpg/250px-The_Mozart_Family_-_Wolfgang_Amadeus_Mozart_headshot.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6e/Joseph_Karl_Stieler%27s_Beethoven_mit_dem_Manuskript_der_Missa_solemnis.jpg/250px-Joseph_Karl_Stieler%27s_Beethoven_mit_dem_Manuskript_der_Missa_solemnis.jpg"
  "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4c/Vincent_van_Gogh_-_Self-Portrait_-_Google_Art_Project_%28454045%29.jpg/250px-Vincent_van_Gogh_-_Self-Portrait_-_Google_Art_Project_%28454045%29.jpg"
)

OK=0; SKIP=0; FAIL=0
for i in "${!URLS[@]}"; do
  idx=$((i + 1))
  url="${URLS[$i]}"
  # 从 URL 末尾提取扩展名
  ext="${url##*.}"
  [[ "$ext" == *"%"* || "$ext" == *"?"* ]] && ext="jpg"
  filename="${NAMES[$idx]}.${ext}"
  out="$DEST/$filename"

  if [[ -s "$out" ]]; then
    echo "[$idx/36] ⏭  已存在 $filename"
    ((SKIP++))
    continue
  fi

  # 用 curl：跟随 5 次重定向，超时 30s，重试 2 次；维基百科要求带 User-Agent
  success=0
  for try in 1 2 3; do
    if curl -fSL --max-time 30 -A "Mozilla/5.0 IdeaParty/1.0 (preset avatar downloader)" \
         -o "$out.tmp" "$url" 2>/dev/null; then
      mv "$out.tmp" "$out"
      size=$(stat -f%z "$out" 2>/dev/null || stat -c%s "$out" 2>/dev/null)
      if [[ $size -gt 1000 ]]; then
        echo "[$idx/36] ✓ $filename (${size}B)"
        ((OK++)); success=1
        break
      else
        echo "[$idx/36] ✗ $filename 太小 (${size}B)，重试 $try/3"
        rm -f "$out" "$out.tmp"
      fi
    else
      echo "[$idx/36] ✗ curl 失败 $filename，重试 $try/3"
      rm -f "$out" "$out.tmp"
    fi
    sleep 1
  done
  [[ $success -eq 0 ]] && ((FAIL++))
done

echo ""
echo "===== 完成：成功 $OK · 跳过 $SKIP · 失败 $FAIL ====="
[[ $FAIL -gt 0 ]] && exit 1
exit 0