package jdos.hardware.mame;

import java.util.Arrays;
import java.util.LinkedList;

public class Poly {
    /***************************************************************************
        CONSTANTS
    ***************************************************************************/
    static public final int WORK_MAX_THREADS = 4;

    static public final int SCANLINES_PER_BUCKET          =  8;
    static public final int CACHE_LINE_SIZE               =  64;          /* this is a general guess */
    static public final int TOTAL_BUCKETS                 =  (512 / SCANLINES_PER_BUCKET);
    static public final int UNITS_PER_POLY                =  (100 / SCANLINES_PER_BUCKET);

    static public final int MAX_VERTEX_PARAMS                  = 6;
    static public final int MAX_POLYGON_VERTS                  = 32;
    
    static public final int POLYFLAG_INCLUDE_BOTTOM_EDGE       = 0x01;
    static public final int POLYFLAG_INCLUDE_RIGHT_EDGE        = 0x02;
    static public final int POLYFLAG_NO_WORK_QUEUE             = 0x04;
    static public final int POLYFLAG_ALLOW_QUADS               = 0x08;
    

    /***************************************************************************
        TYPE DEFINITIONS
    ***************************************************************************/

    /* input vertex data */
    static final class poly_vertex
    {
        static public poly_vertex[] create(int count) {
            poly_vertex[] result = new poly_vertex[count];
            for (int i=0;i<result.length;i++)
                result[i] = new poly_vertex();
            return result;
        }
    	float       x;                          /* X coordinate */
    	float       y;                          /* Y coordinate */
    	float[]     p = new float[MAX_VERTEX_PARAMS];       /* interpolated parameter values */
    }


    /* poly_param_extent describes information for a single parameter in an extent */
    static final class poly_param_extent
    {
    	float       start;                      /* parameter value at starting X,Y */
    	float       dpdx;                       /* dp/dx relative to starting X */
    }


    /* poly_extent describes start/end points for a scanline, along with per-scanline parameters */
    static final class poly_extent
    {
        static public poly_extent[] create(int count) {
            poly_extent[] result = new poly_extent[count];
            for (int i=0;i<result.length;i++)
                result[i] = new poly_extent();
            return result;
        }

        public poly_extent() {
            for (int i=0;i<param.length;i++) {
                param[i] = new poly_param_extent();
            }
        }
    	int       startx;                     /* starting X coordinate (inclusive) */
    	int       stopx;                      /* ending X coordinate (exclusive) */
    	poly_param_extent[] param = new poly_param_extent[MAX_VERTEX_PARAMS]; /* starting and dx values for each parameter */
    }

    /* tri_extent describes start/end points for a scanline */
    static final class tri_extent
    {
    	int       startx;                     /* starting X coordinate (inclusive) */
    	int       stopx;                      /* ending X coordinate (exclusive) */
    }


    /* single set of polygon per-parameter data */
    static final class poly_param
    {
    	float       start;                      /* parameter value at starting X,Y */
    	float       dpdx;                       /* dp/dx relative to starting X */
    	float       dpdy;                       /* dp/dy relative to starting Y */
    }


    /* poly edge is used internally for quad rendering */
    static final class poly_edge
    {
    	poly_edge           next;                   /* next edge in sequence */
    	int                 index;                  /* index of this edge */
    	poly_vertex         v1;                     /* pointer to first vertex */
    	poly_vertex         v2;                     /* pointer to second vertex */
    	float               dxdy;                   /* dx/dy along the edge */
    	float[]             dpdy = new float[MAX_VERTEX_PARAMS];/* per-parameter dp/dy values */
    }


    /* poly section is used internally for quad rendering */
    static final class poly_section
    {
    	poly_edge           ledge;                  /* pointer to left edge */
    	poly_edge           redge;                  /* pointer to right edge */
    	float               ybottom;                /* bottom of this section */
    }


    /* work_unit_shared is a common set of data shared between tris and quads */
    static class work_unit
    {
        int setCountNext(int value) {
            synchronized (this) {
                int result = count_next;
                count_next = value;
                return result;
            }
        }
        int orCountNext(int value) {
            synchronized (this) {
                int result = count_next;
                count_next |= value;
                return result;
            }
        }
    	polygon_info      polygon;                /* pointer to polygon */
    	int               count_next;             /* number of scanlines and index of next item to process */
    	int               scanline;               /* starting scanline and count */
        int               previtem;               /* index of previous item in the same bucket */
        int               index;
    }


    /* tri_work_unit is a triangle-specific work-unit */
    static final class tri_work_unit extends work_unit
    {
        static public tri_work_unit[] create(int count) {
            tri_work_unit[] result = new tri_work_unit[count];
            for (int i=0;i<result.length;i++) {
                result[i] = new tri_work_unit();
                result[i].index = i;
            }
            return result;
        }
        public tri_work_unit() {
            for (int i=0;i<extent.length;i++) {
                extent[i] = new tri_extent();
            }
        }
    	tri_extent[] extent = new tri_extent[SCANLINES_PER_BUCKET]; /* array of scanline extents */
    }


    /* quad_work_unit is a quad-specific work-unit */
    static final class quad_work_unit extends work_unit
    {
        static public quad_work_unit[] create(int count) {
            quad_work_unit[] result = new quad_work_unit[count];
            for (int i=0;i<result.length;i++) {
                result[i] = new quad_work_unit();
                result[i].index = i;
            }
            return result;
        }
        public quad_work_unit() {
            for (int i=0;i<extent.length;i++) {
                extent[i] = new poly_extent();
            }
        }
    	poly_extent[] extent = new poly_extent[SCANLINES_PER_BUCKET]; /* array of scanline extents */
    }

    static interface poly_draw_scanline_func {
        public void call(byte[] dest, int destOffset, int scanline, poly_extent extent, Object extradata, int threadid);
    }

    /* polygon_info describes a single polygon, which includes the poly_params */
    static final class polygon_info
    {
        public static polygon_info[] create(int count) {
            polygon_info[] result = new polygon_info[count];
            for (int i=0;i<result.length;i++)
                result[i] = new polygon_info();
            return result;
        }
        public polygon_info() {
            for (int i=0;i<param.length;i++) {
                param[i] = new poly_param();
            }
        }
    	poly_manager        poly;                   /* pointer back to the poly manager */
    	byte[]              dest;                   /* pointer to the destination we are rendering to */
        int                 destOffset;
    	Object              extra;                  /* extra data pointer */
    	int                 numparams;              /* number of parameters for this polygon  */
    	int                 numverts;               /* number of vertices in this polygon */
    	poly_draw_scanline_func     callback;               /* callback to handle a scanline's worth of work */
    	int                 xorigin;                /* X origin for all parameters */
    	int                 yorigin;                /* Y origin for all parameters */
    	poly_param[]        param = new poly_param[MAX_VERTEX_PARAMS];/* array of parameter data */
    }


    /* full poly manager description */
    static final class poly_manager
    {
    	/* queue management */
        LinkedList<work_unit> queue = null;
        
    	/* triangle work units */
    	work_unit[]         unit;                   /* array of work unit pointers */
    	int                 unit_next;              /* index of next unit to allocate */
        int                 unit_count;             /* number of work units available */

    	/* quad work units */
        int                 quadunit_next;          /* index of next unit to allocate */
        int                 quadunit_count;         /* number of work units available */
        int                 quadunit_size;          /* size of each work unit, in bytes */

    	/* poly data */
    	polygon_info[]      polygon;                /* array of polygon pointers */
        int                 polygon_next;           /* index of next polygon to allocate */
        int                 polygon_count;          /* number of polygon items available */

    	/* extra data */
    	Object[]            extra;                  /* array of extra data pointers */
        int                 extra_next;             /* index of next extra data to allocate */
        int                 extra_count;            /* number of extra data items available */

    	/* misc data */
        int                 flags;                  /* flags */

    	/* buckets */
        int[]               unit_bucket = new int[TOTAL_BUCKETS]; /* buckets for tracking unit usage */

    	/* statistics */
        int                 triangles;              /* number of triangles queued */
        int                 quads;                  /* number of quads queued */
    	long                pixels;                 /* number of pixels rendered */
        int                 unit_waits;             /* number of times we waited for a unit */
        int                 unit_max;               /* maximum units used */
        int                 polygon_waits;          /* number of times we waited for a polygon */
        int                 polygon_max;            /* maximum polygons used */
        int                 extra_waits;            /* number of times we waited for an extra data */
        int                 extra_max;              /* maximum extra data used */
        int[]               conflicts = new int[WORK_MAX_THREADS]; /* number of conflicts found, per thread */
    	int[]               resolved = new int[WORK_MAX_THREADS]; /* number of conflicts resolved, per thread */
    }

    /***************************************************************************
        INLINE FUNCTIONS
    ***************************************************************************/
    
    /*-------------------------------------------------
        round_coordinate - round a coordinate to
        an integer, following rules that 0.5 rounds
        down
    -------------------------------------------------*/
    
    static private int round_coordinate(float value)
    {
        int result = (int)Math.floor(value);
        return result + ((value - (float)result > 0.5f)?1:0);
    }
    
    
    /*-------------------------------------------------
        convert_tri_extent_to_poly_extent - convert
        a simple tri_extent to a full poly_extent
    -------------------------------------------------*/
    
    static private void convert_tri_extent_to_poly_extent(poly_extent dstextent, tri_extent srcextent, polygon_info polygon, int y)
    {
    	/* copy start/stop always */
    	dstextent.startx = srcextent.startx;
    	dstextent.stopx = srcextent.stopx;
    
    	/* if we have parameters, process them as well */
    	for (int paramnum = 0; paramnum < polygon.numparams; paramnum++)
    	{
    		dstextent.param[paramnum].start = polygon.param[paramnum].start + srcextent.startx * polygon.param[paramnum].dpdx + y * polygon.param[paramnum].dpdy;
    		dstextent.param[paramnum].dpdx = polygon.param[paramnum].dpdx;
    	}
    }
    
    
    /*-------------------------------------------------
        interpolate_vertex - interpolate values in
        a vertex based on p[0] crossing the clipval
    -------------------------------------------------*/
    
    static private void interpolate_vertex(poly_vertex outv, poly_vertex v1, poly_vertex v2, int paramcount, float clipval)
    {
    	float frac = (clipval - v1.p[0]) / (v2.p[0] - v1.p[0]);
    	int paramnum;
    
    	/* create a new one at the intersection point */
    	outv.x = v1.x + frac * (v2.x - v1.x);
    	outv.y = v1.y + frac * (v2.y - v1.y);
    	for (paramnum = 0; paramnum < paramcount; paramnum++)
    		outv.p[paramnum] = v1.p[paramnum] + frac * (v2.p[paramnum] - v1.p[paramnum]);
    }
    
    
    /*-------------------------------------------------
        copy_vertex - copy vertex data from one to
        another
    -------------------------------------------------*/
    
    static private void copy_vertex(poly_vertex outv, poly_vertex v, int paramcount)
    {
    	int paramnum;
    
    	outv.x = v.x;
    	outv.y = v.y;
    	for (paramnum = 0; paramnum < paramcount; paramnum++)
    		outv.p[paramnum] = v.p[paramnum];
    }
    
    
    /*-------------------------------------------------
        allocate_polygon - allocate a new polygon
        object, blocking if we run out
    -------------------------------------------------*/
    
    static private polygon_info allocate_polygon(poly_manager poly, int miny, int maxy)
    {
    	/* wait for a work item if we have to */
    	if (poly.polygon_next + 1 > poly.polygon_count)
    	{
    		poly_wait(poly, "Out of polygons");
    		poly.polygon_waits++;
    	}
    	else if (poly.unit_next + (maxy - miny) / SCANLINES_PER_BUCKET + 2 > poly.unit_count)
    	{
    		poly_wait(poly, "Out of work units");
    		poly.unit_waits++;
    	}
    	poly.polygon_max = Math.max(poly.polygon_max, poly.polygon_next + 1);
    	return poly.polygon[poly.polygon_next++];
    }
    
    /***************************************************************************
        INITIALIZATION/TEARDOWN
    ***************************************************************************/
    
    /*-------------------------------------------------
        poly_alloc - initialize a new polygon
        manager
    -------------------------------------------------*/
    
    static poly_manager poly_alloc(int max_polys, int flags, Object[] extra)
    {
    	poly_manager poly = new poly_manager();
    
    	/* allocate the manager itself */
    	poly.flags = flags;
    
    	/* allocate polygons */
    	poly.polygon_count = Math.max(max_polys, 1);
    	poly.polygon_next = 0;
    	poly.polygon = polygon_info.create(poly.polygon_count);
    
    	/* allocate extra data */
    	poly.extra_count = poly.polygon_count;
    	poly.extra_next = 1;
    	poly.extra = extra;
    
    	/* allocate triangle work units */
    	poly.unit_count = Math.min(poly.polygon_count * UNITS_PER_POLY, 65535);
    	poly.unit_next = 0;
    	poly.unit = (flags & POLYFLAG_ALLOW_QUADS)!=0? quad_work_unit.create(poly.unit_count): tri_work_unit.create(poly.unit_count);

    
    	/* create the work queue */
    	if ((flags & POLYFLAG_NO_WORK_QUEUE)==0)
    		poly.queue = new LinkedList<work_unit>();

    	return poly;
    }
    
    
    /*-------------------------------------------------
        poly_free - free a polygon manager
    -------------------------------------------------*/
    
    static void poly_free(poly_manager poly)
    {
//    #if KEEP_STATISTICS
//    {
//    	int i, conflicts = 0, resolved = 0;
//    	for (i = 0; i < ARRAY_LENGTH(poly.conflicts); i++)
//    	{
//    		conflicts += poly.conflicts[i];
//    		resolved += poly.resolved[i];
//    	}
//    	printf("Total triangles = %d\n", poly.triangles);
//    	printf("Total quads = %d\n", poly.quads);
//    	if (poly.pixels > 1000000000)
//    		printf("Total pixels   = %d%09d\n", (UINT32)(poly.pixels / 1000000000), (UINT32)(poly.pixels % 1000000000));
//    	else
//    		printf("Total pixels   = %d\n", (UINT32)poly.pixels);
//    	printf("Conflicts:  %d resolved, %d total\n", resolved, conflicts);
//    	printf("Units:      %5d used, %5d allocated, %5d waits, %4d bytes each, %7d total\n", poly.unit_max, poly.unit_count, poly.unit_waits, poly.unit_size, poly.unit_count * poly.unit_size);
//    	printf("Polygons:   %5d used, %5d allocated, %5d waits, %4d bytes each, %7d total\n", poly.polygon_max, poly.polygon_count, poly.polygon_waits, poly.polygon_size, poly.polygon_count * poly.polygon_size);
//    	printf("Extra data: %5d used, %5d allocated, %5d waits, %4d bytes each, %7d total\n", poly.extra_max, poly.extra_count, poly.extra_waits, poly.extra_size, poly.extra_count * poly.extra_size);
//    }
//    #endif
    
    	/* free the work queue */
//    	if (poly.queue != null)
//    		osd_work_queue_free(poly.queue);
    }
    
    /***************************************************************************
        COMMON FUNCTIONS
    ***************************************************************************/

    /*-------------------------------------------------
        poly_wait - wait for all pending rendering
        to complete
    -------------------------------------------------*/

    static void poly_wait(poly_manager poly, String debug_reason)
    {
//    	osd_ticks_t time;

    	/* remember the start time if we're logging */
//    	if (LOG_WAITS)
//    		time = get_profile_ticks();

    	/* wait for all pending work items to complete */
    	if (poly.queue != null) {
            synchronized (poly.queue) {
                if (poly.queue.size()>0) {
                    try {
                        poly.queue.wait(1000000);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }

    	/* if we don't have a queue, just run the whole list now */
    	else
    	{
    		int unitnum;
    		for (unitnum = 0; unitnum < poly.unit_next; unitnum++)
    			poly_item_callback(poly.unit[unitnum], 0);
    	}

    	/* log any long waits */
//    	if (LOG_WAITS)
//    	{
//    		time = get_profile_ticks() - time;
//    		if (time > LOG_WAIT_THRESHOLD)
//    			logerror("Poly:Waited %d cycles for %s\n", (int)time, debug_reason);
//    	}

    	/* reset the state */
    	poly.polygon_next = poly.unit_next = 0;
        Arrays.fill(poly.unit_bucket, 0xffff);

    	/* we need to preserve the last extra data that was supplied */
    	if (poly.extra_next > 1)
            poly.extra[poly.extra_next-1]=poly.extra[0];
    	poly.extra_next = 1;
    }

    /*-------------------------------------------------
        poly_get_extra_data - get a pointer to the
        extra data for the next polygon
    -------------------------------------------------*/

    static Object poly_get_extra_data(poly_manager poly)
    {
    	/* wait for a work item if we have to */
    	if (poly.extra_next + 1 > poly.extra_count)
    	{
    		poly_wait(poly, "Out of extra data");
    		poly.extra_waits++;
    	}

    	/* return a pointer to the extra data for the next item */
    	poly.extra_max = Math.max(poly.extra_max, poly.extra_next + 1);
    	return poly.extra[poly.extra_next++];
    }

    /***************************************************************************
        CORE TRIANGLE RENDERING
    ***************************************************************************/

    /*-------------------------------------------------
        poly_render_triangle - render a single
        triangle given 3 vertexes
    -------------------------------------------------*/

    static int poly_render_triangle(poly_manager poly, byte[] dest, int destOffset, VoodooCommon.rectangle cliprect, poly_draw_scanline_func callback, int paramcount, poly_vertex v1, poly_vertex v2, poly_vertex v3)
    {
    	float dxdy_v1v2, dxdy_v1v3, dxdy_v2v3;
    	poly_vertex tv;
    	int curscan, scaninc;
    	polygon_info polygon;
        int v1yclip, v3yclip;
        int v1y, v3y, v1x;
        int pixels = 0;
        int startunit;

    	/* first sort by Y */
    	if (v2.y < v1.y)
    	{
    		tv = v1;
    		v1 = v2;
    		v2 = tv;
    	}
    	if (v3.y < v2.y)
    	{
    		tv = v2;
    		v2 = v3;
    		v3 = tv;
    		if (v2.y < v1.y)
    		{
    			tv = v1;
    			v1 = v2;
    			v2 = tv;
    		}
    	}

    	/* compute some integral X/Y vertex values */
    	v1x = round_coordinate(v1.x);
    	v1y = round_coordinate(v1.y);
    	v3y = round_coordinate(v3.y);

    	/* clip coordinates */
    	v1yclip = v1y;
    	v3yclip = v3y + ((poly.flags & POLYFLAG_INCLUDE_BOTTOM_EDGE)!=0 ? 1 : 0);
    	v1yclip = Math.max(v1yclip, cliprect.min_y);
    	v3yclip = Math.min(v3yclip, cliprect.max_y + 1);
    	if (v3yclip - v1yclip <= 0)
    		return 0;

    	/* allocate a new polygon */
    	polygon = allocate_polygon(poly, v1yclip, v3yclip);

    	/* fill in the polygon information */
    	polygon.poly = poly;
    	polygon.dest = dest;
    	polygon.callback = callback;
    	polygon.extra = poly.extra[poly.extra_next - 1];
    	polygon.numparams = paramcount;
    	polygon.numverts = 3;

    	/* set the start X/Y coordinates */
    	polygon.xorigin = v1x;
    	polygon.yorigin = v1y;

    	/* compute the slopes for each portion of the triangle */
    	dxdy_v1v2 = (v2.y == v1.y) ? 0.0f : (v2.x - v1.x) / (v2.y - v1.y);
    	dxdy_v1v3 = (v3.y == v1.y) ? 0.0f : (v3.x - v1.x) / (v3.y - v1.y);
    	dxdy_v2v3 = (v3.y == v2.y) ? 0.0f : (v3.x - v2.x) / (v3.y - v2.y);

    	/* compute the X extents for each scanline */
    	startunit = poly.unit_next;
    	for (curscan = v1yclip; curscan < v3yclip; curscan += scaninc)
    	{
    		int bucketnum = (curscan / SCANLINES_PER_BUCKET) % TOTAL_BUCKETS;
    		int unit_index = poly.unit_next++;
    		tri_work_unit unit = (tri_work_unit)poly.unit[unit_index];
    		int extnum;

    		/* determine how much to advance to hit the next bucket */
    		scaninc = SCANLINES_PER_BUCKET - curscan % SCANLINES_PER_BUCKET;

    		/* fill in the work unit basics */
    		unit.polygon = polygon;
    		unit.count_next = Math.min(v3yclip - curscan, scaninc);
    		unit.scanline = curscan;
    		unit.previtem = poly.unit_bucket[bucketnum];
    		poly.unit_bucket[bucketnum] = unit_index;

    		/* iterate over extents */
    		for (extnum = 0; extnum < unit.count_next; extnum++)
    		{
    			float fully = (float)(curscan + extnum) + 0.5f;
    			float startx = v1.x + (fully - v1.y) * dxdy_v1v3;
    			float stopx;
    			int istartx, istopx;

    			/* compute the ending X based on which part of the triangle we're in */
    			if (fully < v2.y)
    				stopx = v1.x + (fully - v1.y) * dxdy_v1v2;
    			else
    				stopx = v2.x + (fully - v2.y) * dxdy_v2v3;

    			/* clamp to full pixels */
    			istartx = round_coordinate(startx);
    			istopx = round_coordinate(stopx);

    			/* force start < stop */
    			if (istartx > istopx)
    			{
    				int temp = istartx;
    				istartx = istopx;
    				istopx = temp;
    			}

    			/* include the right edge if requested */
    			if ((poly.flags & POLYFLAG_INCLUDE_RIGHT_EDGE)!=0)
    				istopx++;

    			/* apply left/right clipping */
    			if (istartx < cliprect.min_x)
    				istartx = cliprect.min_x;
    			if (istopx > cliprect.max_x)
    				istopx = cliprect.max_x + 1;

    			/* set the extent and update the total pixel count */
    			if (istartx >= istopx)
    				istartx = istopx = 0;
    			unit.extent[extnum].startx = istartx;
    			unit.extent[extnum].stopx = istopx;
    			pixels += istopx - istartx;
    		}
    	}
    	poly.unit_max = Math.max(poly.unit_max, poly.unit_next);

    	/* compute parameter starting points and deltas */
    	if (paramcount > 0)
    	{
    		float a00 = v2.y - v3.y;
    		float a01 = v3.x - v2.x;
    		float a02 = v2.x*v3.y - v3.x*v2.y;
    		float a10 = v3.y - v1.y;
    		float a11 = v1.x - v3.x;
    		float a12 = v3.x*v1.y - v1.x*v3.y;
    		float a20 = v1.y - v2.y;
    		float a21 = v2.x - v1.x;
    		float a22 = v1.x*v2.y - v2.x*v1.y;
    		float det = a02 + a12 + a22;

    		if(Math.abs(det) < 0.001) {
    			for (int paramnum = 0; paramnum < paramcount; paramnum++)
    			{
    				poly_param params = polygon.param[paramnum];
    				params.dpdx = 0;
    				params.dpdy = 0;
    				params.start = v1.p[paramnum];
    			}
    		}
    		else
    		{
    			float idet = 1/det;
    			for (int paramnum = 0; paramnum < paramcount; paramnum++)
    			{
    				poly_param params = polygon.param[paramnum];
    				params.dpdx  = idet*(v1.p[paramnum]*a00 + v2.p[paramnum]*a10 + v3.p[paramnum]*a20);
    				params.dpdy  = idet*(v1.p[paramnum]*a01 + v2.p[paramnum]*a11 + v3.p[paramnum]*a21);
    				params.start = idet*(v1.p[paramnum]*a02 + v2.p[paramnum]*a12 + v3.p[paramnum]*a22);
    			}
    		}
    	}

    	/* enqueue the work items */
    	if (poly.queue != null) {
            addWork(poly.queue, poly.unit, startunit, poly.unit_next-1);
        }

    	/* return the total number of pixels in the triangle */
    	poly.triangles++;
    	poly.pixels += pixels;
    	return pixels;
    }

    static void addWork(LinkedList queue, work_unit[] units, int startIndex, int stopIndex) {
        for (int i=startIndex;i<=stopIndex;i++)
            poly_item_callback(units[i], 0);
    }

    /*-------------------------------------------------
        poly_render_triangle_fan - render a set of
        triangles in a fan
    -------------------------------------------------*/

    static int poly_render_triangle_fan(poly_manager poly, byte[] dest, int destOffset, final VoodooCommon.rectangle cliprect, poly_draw_scanline_func callback, int paramcount, int numverts, poly_vertex[] v)
    {
    	int pixels = 0;
    	int vertnum;

    	/* iterate over vertices */
    	for (vertnum = 2; vertnum < numverts; vertnum++)
    		pixels += poly_render_triangle(poly, dest, destOffset, cliprect, callback, paramcount, v[0], v[vertnum - 1], v[vertnum]);
    	return pixels;
    }


    /*-------------------------------------------------
        poly_render_triangle_custom - perform a custom
        render of an object, given specific extents
    -------------------------------------------------*/

    static int poly_render_triangle_custom(poly_manager poly, byte[] dest, int destOffset, final VoodooCommon.rectangle cliprect, poly_draw_scanline_func callback, int startscanline, int numscanlines, poly_extent[] extents)
    {
//    	INT32 curscan, scaninc;
//    	polygon_info *polygon;
//    	INT32 v1yclip, v3yclip;
//    	INT32 pixels = 0;
//    	UINT32 startunit;
//
//    	/* clip coordinates */
//    	v1yclip = MAX(startscanline, cliprect.min_y);
//    	v3yclip = MIN(startscanline + numscanlines, cliprect.max_y + 1);
//    	if (v3yclip - v1yclip <= 0)
//    		return 0;
//
//    	/* allocate a new polygon */
//    	polygon = allocate_polygon(poly, v1yclip, v3yclip);
//
//    	/* fill in the polygon information */
//    	polygon.poly = poly;
//    	polygon.dest = dest;
//    	polygon.callback = callback;
//    	polygon.extra = poly.extra[poly.extra_next - 1];
//    	polygon.numparams = 0;
//    	polygon.numverts = 3;
//
//    	/* compute the X extents for each scanline */
//    	startunit = poly.unit_next;
//    	for (curscan = v1yclip; curscan < v3yclip; curscan += scaninc)
//    	{
//    		UINT32 bucketnum = ((UINT32)curscan / SCANLINES_PER_BUCKET) % TOTAL_BUCKETS;
//    		UINT32 unit_index = poly.unit_next++;
//    		tri_work_unit *unit = &poly.unit[unit_index].tri;
//    		int extnum;
//
//    		/* determine how much to advance to hit the next bucket */
//    		scaninc = SCANLINES_PER_BUCKET - (UINT32)curscan % SCANLINES_PER_BUCKET;
//
//    		/* fill in the work unit basics */
//    		unit.shared.polygon = polygon;
//    		unit.shared.count_next = MIN(v3yclip - curscan, scaninc);
//    		unit.shared.scanline = curscan;
//    		unit.shared.previtem = poly.unit_bucket[bucketnum];
//    		poly.unit_bucket[bucketnum] = unit_index;
//
//    		/* iterate over extents */
//    		for (extnum = 0; extnum < unit.shared.count_next; extnum++)
//    		{
//    			const poly_extent *extent = &extents[(curscan + extnum) - startscanline];
//    			INT32 istartx = extent.startx, istopx = extent.stopx;
//
//    			/* force start < stop */
//    			if (istartx > istopx)
//    			{
//    				INT32 temp = istartx;
//    				istartx = istopx;
//    				istopx = temp;
//    			}
//
//    			/* apply left/right clipping */
//    			if (istartx < cliprect.min_x)
//    				istartx = cliprect.min_x;
//    			if (istopx > cliprect.max_x)
//    				istopx = cliprect.max_x + 1;
//
//    			/* set the extent and update the total pixel count */
//    			unit.extent[extnum].startx = istartx;
//    			unit.extent[extnum].stopx = istopx;
//    			if (istartx < istopx)
//    				pixels += istopx - istartx;
//    		}
//    	}
//    #if KEEP_STATISTICS
//    	poly.unit_max = MAX(poly.unit_max, poly.unit_next);
//    #endif
//
//    	/* enqueue the work items */
//    	if (poly.queue != NULL)
//    		osd_work_item_queue_multiple(poly.queue, poly_item_callback, poly.unit_next - startunit, poly.unit[startunit], poly.unit_size, WORK_ITEM_FLAG_AUTO_RELEASE);
//
//    	/* return the total number of pixels in the object */
//    	poly.triangles++;
//    	poly.pixels += pixels;
//    	return pixels;
        return 0;
    }
    
    /*-------------------------------------------------
        poly_item_callback - callback for each poly
        item
    -------------------------------------------------*/
    
    static void poly_item_callback(work_unit unit, int threadid)
    {
    	while (true)
    	{
    		polygon_info polygon = unit.polygon;
    		int count = unit.count_next & 0xffff;
    		int orig_count_next;
    		int curscan;
    
    		/* if our previous item isn't done yet, enqueue this item to the end and proceed */
    		if (unit.previtem != 0xffff)
    		{
    			work_unit prevunit = polygon.poly.unit[unit.previtem];
    			if (prevunit.count_next != 0)
    			{
    				int unitnum = unit.index;
    				int new_count_next;

                    orig_count_next = prevunit.orCountNext(unitnum << 16);

    				/* track resolved conflicts */
    				polygon.poly.conflicts[threadid]++;
    				if (orig_count_next != 0)
    					polygon.poly.resolved[threadid]++;

    				/* if we succeeded, skip out early so we can do other work */
    				if (orig_count_next != 0)
    					break;
    			}
    		}
    
    		/* iterate over extents */
    		for (curscan = 0; curscan < count; curscan++)
    		{
    			if (polygon.numverts == 3)
    			{
    				poly_extent tmpextent = new poly_extent();
    				convert_tri_extent_to_poly_extent(tmpextent, ((tri_work_unit)unit).extent[curscan], polygon, unit.scanline + curscan);
    				polygon.callback.call(polygon.dest, polygon.destOffset, unit.scanline + curscan, tmpextent, polygon.extra, threadid);
    			}
    			else {
    				polygon.callback.call(polygon.dest, polygon.destOffset, unit.scanline + curscan, ((quad_work_unit)unit).extent[curscan], polygon.extra, threadid);
                }
    		}
    
    		/* set our count to 0 and re-fetch the original count value */
            orig_count_next = unit.setCountNext(0);

    		/* if we have no more work to do, do nothing */
    		orig_count_next >>= 16;
    		if (orig_count_next == 0)
    			break;
    		unit = polygon.poly.unit[orig_count_next];
    	}
    }
}