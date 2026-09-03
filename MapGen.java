import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

// Real map generator: coherent geography instead of scattered clusters.
//
//  1) SITE PLACEMENT: villages + dungeons claim large validated zones first
//  2) ROAD NETWORK: Dijkstra shortest paths (avoiding water) connect every
//     village to its 2 nearest neighbours + all dungeons, carved as 'p' tiles
//  3) VILLAGE LAYOUT: plaza + cross street grid + houses facing streets,
//     fenced farms on the outskirts, wells/lanterns/signposts placed on
//     street corners
//  4) DUNGEON FLOOR PLAN: room-and-corridor plan (BSP-lite), walls around
//     rooms, gate at the road entrance, treasure in the far room, graveyard
//     in another, garrison in between
//
// The world text ('g'/'w'/'p') is ground truth; drawing comes from the
// semantic sprite registry (assets/map/*) + tile autotiling.
public class MapGen {
    private final char[][] text;
    private final int N;
    private final Random rand;
    final List<Point> villages = new ArrayList<>();
    final List<Point> dungeons = new ArrayList<>();
    // road tiles (for entity placement rules)
    final Set<Long> roadTiles = new HashSet<>();

    MapGen(char[][] text, long seed) {
        this.text = text;
        this.N = text.length;
        this.rand = new Random(seed ^ 0x5EED_1234);
    }

    boolean isGrass(int x, int y) {
        return x >= 0 && y >= 0 && x < N && y < N && text[y][x] == 'g';
    }

    // all-grass w x h rectangle with a 1-tile ring, away from border
    int[] findSite(int w, int h, int margin, int tries, Set<Rectangle> taken) {
        for (int t = 0; t < tries; t++) {
            int x = margin + rand.nextInt(N - 2 * margin - w);
            int y = margin + rand.nextInt(N - 2 * margin - h);
            boolean ok = true;
            for (int yy = y - 1; yy <= y + h && ok; yy++)
                for (int xx = x - 1; xx <= x + w && ok; xx++)
                    ok &= isGrass(xx, yy);
            if (!ok) continue;
            Rectangle zone = new Rectangle(x - 2, y - 2, w + 4, h + 4);
            boolean clash = false;
            for (Rectangle r : taken) if (r.intersects(zone)) { clash = true; break; }
            if (clash) continue;
            taken.add(zone);
            return new int[]{x, y};
        }
        return null;
    }

    // note: site rects registered in generate() AFTER findSite returns

    // Dijkstra over grass tiles from (sx,sy); returns parent map to targets.
    // Roads avoid water (huge cost) and existing roads (small bonus).
    private long[] dijkstra(int sx, int sy, List<int[]> targets) {
        int M = N * N;
        long[] dist = new long[M];
        int[] parent = new int[M];
        Arrays.fill(dist, Long.MAX_VALUE);
        Arrays.fill(parent, -1);
        java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        dist[sy * N + sx] = 0;
        pq.add(new long[]{0, sx, sy});
        boolean[] done = new boolean[M];
        Set<Integer> targetSet = new HashSet<>();
        for (int[] tg : targets) targetSet.add(tg[1] * N + tg[0]);
        int found = 0;
        while (!pq.isEmpty() && found < targetSet.size()) {
            long[] top = pq.poll();
            int cx = (int) top[1], cy = (int) top[2];
            int ci = cy * N + cx;
            if (done[ci]) continue;
            done[ci] = true;
            if (targetSet.contains(ci)) found++;
            int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
            for (int[] d : dirs) {
                int nx = cx + d[0], ny = cy + d[1];
                if (nx < 1 || ny < 1 || nx >= N - 1 || ny >= N - 1) continue;
                int ni = ny * N + nx;
                if (done[ni]) continue;
                char t = text[ny][nx];
                long cost = t == 'w' ? 50 : (t == 'p' ? 1 : 3); // water costly, road cheap, grass normal
                if (blockedBySite(nx, ny)) cost = 1000000;       // never route through another site
                if (dist[ci] + cost < dist[ni]) {
                    dist[ni] = dist[ci] + cost;
                    parent[ni] = ci;
                    pq.add(new long[]{dist[ni], nx, ny});
                }
            }
        }
        // fold parent into a single long[] payload: [idx]->parent idx
        long[] out = new long[M];
        for (int i = 0; i < M; i++) out[i] = parent[i];
        return out;
    }

    // carve a road from (ax,ay) to (bx,by) along the Dijkstra path
    void carveRoad(int ax, int ay, int bx, int by) {
        long[] parent = dijkstra(bx, by, List.of(new int[]{ax, ay}));
        int cur = ay * N + ax;
        int start = cur;
        // walk parents from a -> b
        List<Integer> path = new ArrayList<>();
        int node = ay * N + ax;
        // dijkstra ran FROM b: parents lead back to b
        while (node != -1) { path.add(node); node = (int) parent[node]; }
        if (path.isEmpty()) return;
        for (int idx : path) {
            int x = idx % N, y = idx / N;
            if (text[y][x] == 'g' || text[y][x] == 'p') {
                text[y][x] = 'p';
                roadTiles.add(((long) x << 32) | (y & 0xFFFFFFFFL));
            }
        }
        // widen 2-wide for main streets through grass
    }

    // ---- public entry: build the whole structured map ----
    // returns placement info the panel uses for obstacles/entities
    static class Site {
        int x, y; // top-left tile
        int w, h; // size in tiles
        Point centre;
        String kind; // "village" / "dungeon"
    }

    List<Site> generate(int nVillages, int nDungeons) {
        List<Site> sites = new ArrayList<>();
        Set<Rectangle> taken = new HashSet<>();
        // villages: 12x10, dungeons: 13x11
        for (int i = 0; i < nVillages; i++) {
            int[] s = findSite(12, 10, 12, 80, taken);
            if (s == null) continue;
            Site site = new Site();
            site.x = s[0]; site.y = s[1]; site.w = 12; site.h = 10;
            site.centre = new Point((s[0] + 6) * 32, (s[1] + 5) * 32); // px
            site.kind = "village";
            sites.add(site);
            villages.add(new Point(s[0] + 6, s[1] + 5)); // tile coords
        }
        for (int i = 0; i < nDungeons; i++) {
            int[] s = findSite(13, 11, 14, 80, taken);
            if (s == null) continue;
            Site site = new Site();
            site.x = s[0]; site.y = s[1]; site.w = 13; site.h = 11;
            site.centre = new Point((s[0] + 6) * 32, (s[1] + 5) * 32);
            site.kind = "dungeon";
            sites.add(site);
            dungeons.add(new Point(s[0] + 6, s[1] + 5));
        }
        // register all site interiors as no-go for road routing
        for (Site s : sites) registerSiteRect(s.x, s.y, s.w, s.h);
        // ---- road network: connect sites to each other's BORDERS ----
        // Roads must not cut through village plots: they route to the nearest
        // border tile of the target site (the village street grid takes over
        // from there, and dungeon roads pass through the wall gates).
        for (int i = 0; i < sites.size(); i++) {
            Site si = sites.get(i);
            Integer[] byDist = new Integer[sites.size()];
            for (int k = 0; k < sites.size(); k++) byDist[k] = k;
            final Site f = si;
            Arrays.sort(byDist, (a, b) -> Integer.compare(
                centreDist(f, sites.get(a)), centreDist(f, sites.get(b))));
            int links = si.kind.equals("dungeon") ? 1 : 2;
            for (int k = 0; k < byDist.length && links > 0; k++) {
                int j = byDist[k];
                if (j == i) continue;
                Site sj = sites.get(j);
                int[] from = borderTile(si, sj);
                int[] to = borderTile(sj, si);
                carveRoad(from[0], from[1], to[0], to[1]);
                links--;
            }
        }
        return sites;
    }

    static int centreDist(Site a, Site b) {
        int ax = a.x + a.w / 2, ay = a.y + a.h / 2;
        int bx = b.x + b.w / 2, by = b.y + b.h / 2;
        return Math.abs(ax - bx) + Math.abs(ay - by);
    }

    // nearest border tile of site `s` when arriving from site `from`:
    // the tile on s's perimeter closest to the other site's centre
    int[] borderTile(Site s, Site from) {
        int cx = from.x + from.w / 2, cy = from.y + from.h / 2;
        int bx = s.x, by = s.y;
        long best = Long.MAX_VALUE;
        // walk the perimeter of the site rect
        for (int i = s.x - 1; i <= s.x + s.w; i++) {
            for (int j : new int[]{s.y - 1, s.y + s.h}) {
                long d = (long)(i - cx) * (i - cx) + (long)(j - cy) * (j - cy);
                if (d < best) { best = d; bx = i; by = j; }
            }
        }
        for (int j = s.y; j < s.y + s.h; j++) {
            for (int i : new int[]{s.x - 1, s.x + s.w}) {
                long d = (long)(i - cx) * (i - cx) + (long)(j - cy) * (j - cy);
                if (d < best) { best = d; bx = i; by = j; }
            }
        }
        return new int[]{bx, by};
    }

    final List<Rectangle> siteRects = new ArrayList<>();

    void registerSiteRect(int x, int y, int w, int h) {
        siteRects.add(new Rectangle(x - 1, y - 1, w + 2, h + 2));
    }

    boolean blockedBySite(int x, int y) {
        for (Rectangle r : siteRects) if (r.contains(x, y)) return true;
        return false;
    }

    char[][] text() { return text; }
}
